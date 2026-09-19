package com.teamfp.aistock.domain.user.service;

import com.teamfp.aistock.domain.user.dto.request.PasswordVerifyRequest;
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
    private final UserService userService;
    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final OrderRepository orderRepository;
    private final StockSubscriptionManager stockSubscriptionManager;
    private final RedisPendingOrderService redisPendingOrderService;
    private final RedisTokenService redisTokenService;
    private final EntityManager entityManager;

    @Transactional
    public void withdraw(Long userId, PasswordVerifyRequest request) {
        userService.verifyPassword(userId, request);
        User user = userRepository.findByUserIdAndIsActiveTrue(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        if (user.getRole() == Role.ADMIN && userRepository
                .findAllByRoleAndStatusAndIsActiveTrueForUpdate(Role.ADMIN, UserStatus.ACTIVE).size() <= 1) {
            throw new CustomException(ErrorCode.LAST_ADMIN_SUSPEND_NOT_ALLOWED);
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
        for (String table : java.util.List.of("account_transactions", "charge_requests", "holdings", "orders")) {
            entityManager.createNativeQuery("delete from " + table + " where account_id in (select account_id from accounts where user_id = :userId)")
                    .setParameter("userId", userId).executeUpdate();
        }
        for (String table : java.util.List.of("social_accounts", "investment_profile", "watchlist", "recent_viewed",
                "ai_planning_sessions", "simulations", "notifications", "inquiries", "news_briefing_settings",
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
                pendingOrders.forEach(order -> redisPendingOrderService.removePendingOrder(order.getStockCode(), order.getOrderId()));
                watchCodes.forEach(stockSubscriptionManager::decreaseWatchlistSubscription);
            }
        });
    }
}
