package com.teamfp.aistock.domain.user.service;

import com.teamfp.aistock.domain.admin.service.AuditLogService;
import com.teamfp.aistock.domain.auth.service.InitialAdminService;
import com.teamfp.aistock.domain.user.dto.request.PasswordVerifyRequest;
import com.teamfp.aistock.domain.user.dto.request.UserWithdrawalRequest;
import com.teamfp.aistock.domain.user.entity.*;
import com.teamfp.aistock.domain.user.repository.UserRepository;
import com.teamfp.aistock.domain.account.repository.AccountRepository;
import com.teamfp.aistock.domain.order.repository.OrderRepository;
import com.teamfp.aistock.domain.order.entity.OrderStatus;
import com.teamfp.aistock.domain.stock.service.StockSubscriptionManager;
import com.teamfp.aistock.global.redis.RedisPendingOrderService;
import com.teamfp.aistock.global.redis.RedisTokenService;
import com.teamfp.aistock.global.exception.*;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service @RequiredArgsConstructor
public class UserWithdrawalService {
    static final String ADMIN_DISPOSE_REASON = "관리자 계정 폐기";
    // 관리자 계정 폐기의 인증 코드 틀린 횟수 기준 — 계정별
    static final String ADMIN_DISPOSE_LOCK_PREFIX = "user:";

    private final UserService userService;
    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final OrderRepository orderRepository;
    private final StockSubscriptionManager stockSubscriptionManager;
    private final RedisPendingOrderService redisPendingOrderService;
    private final RedisTokenService redisTokenService;
    private final EntityManager entityManager;
    private final InitialAdminService initialAdminService;
    private final AuditLogService auditLogService;

    @Transactional
    public void withdraw(Long userId, UserWithdrawalRequest request) {
        User user = userRepository.findByUserIdAndIsActiveTrue(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        if (user.getLoginId() == null) {
            if (user.getEmail() == null || request.email() == null || request.email().isBlank()
                    || !user.getEmail().trim().equalsIgnoreCase(request.email().trim())) {
                throw new CustomException(ErrorCode.INVALID_INPUT, "계정에 등록된 본인 이메일을 입력해 주세요.");
            }
        } else {
            if (request.password() == null || request.password().isBlank()) {
                throw new CustomException(ErrorCode.INVALID_INPUT, "비밀번호는 필수 입력 값입니다.");
            }
            userService.verifyPassword(userId, new PasswordVerifyRequest(request.password()));
        }
        // 관리자 계정 폐기(feat/admin-improvements) — 본인 확인에 더해 관리자 인증 코드가 맞아야 한다(계정별로 3번 틀리면
        // 10분 잠금). 마지막 관리자도 폐기할 수 있고, 그러면 관리자가 0명이 되어 회원가입 화면에서 최초 관리자
        // 만들기(InitialAdminService)가 다시 열린다.
        if (user.getRole() == Role.ADMIN) {
            initialAdminService.verifyAdminCode(ADMIN_DISPOSE_LOCK_PREFIX + userId, request.adminCode());
            auditLogService.record(userId, AuditLogService.ACTION_ADMIN_DISPOSE, AuditLogService.TARGET_ADMIN, userId,
                    null, user.getLoginId(), ADMIN_DISPOSE_REASON);
        }
        var accounts = accountRepository.findAllByUserIdForUpdate(userId);
        var pendingOrders = accounts.stream().flatMap(account -> orderRepository
                .findAllByAccountIdOrderByOrderedAtDesc(account.getAccountId()).stream())
                .filter(order -> order.getStatus() == OrderStatus.PENDING).toList();
        @SuppressWarnings("unchecked")
        java.util.List<String> watchCodes = entityManager.createNativeQuery("select stock_code from watchlist where user_id = :userId", String.class)
                .setParameter("userId", userId).getResultList();
        // DB에 FK CASCADE가 없어도 자식부터 삭제되도록 순서를 명시한다.
        entityManager.createNativeQuery("delete from ai_planning_messages where session_id in (select session_id from ai_planning_sessions where user_id = :userId)")
                .setParameter("userId", userId).executeUpdate();
        entityManager.createNativeQuery("delete from news_chat_messages where session_id in (select session_id from news_chat_sessions where user_id = :userId)")
                .setParameter("userId", userId).executeUpdate();
        for (String table : java.util.List.of("account_transactions", "charge_requests", "holdings", "orders")) {
            entityManager.createNativeQuery("delete from " + table + " where account_id in (select account_id from accounts where user_id = :userId)")
                    .setParameter("userId", userId).executeUpdate();
        }
        for (String table : java.util.List.of("social_accounts", "investment_profile", "watchlist", "recent_viewed",
                "ai_planning_sessions", "news_chat_sessions", "simulations", "notifications", "inquiries", "news_briefing_settings",
                "news_briefings", "goal_plans", "planning_preferences", "accounts")) {
            entityManager.createNativeQuery("delete from " + table + " where user_id = :userId")
                    .setParameter("userId", userId).executeUpdate();
        }
        user.deactivate();
        entityManager.flush();
        // 요청이 롤백되면 Redis 구독/주문은 기존 상태를 유지한다.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                redisTokenService.deleteRefreshToken(userId);
                pendingOrders.forEach(order -> {
                    if (redisPendingOrderService.removePendingOrder(order.getStockCode(), order.getOrderId())) {
                        stockSubscriptionManager.decreaseOrderSubscription(order.getStockCode());
                    }
                });
                watchCodes.forEach(stockSubscriptionManager::decreaseWatchlistSubscription);
            }
        });
    }
}
