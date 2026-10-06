package com.teamfp.aistock.domain.admin.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.teamfp.aistock.domain.account.entity.Account;
import com.teamfp.aistock.domain.account.entity.AccountTransaction;
import com.teamfp.aistock.domain.account.entity.ChargeRequest;
import com.teamfp.aistock.domain.account.repository.AccountRepository;
import com.teamfp.aistock.domain.account.repository.AccountTransactionRepository;
import com.teamfp.aistock.domain.account.repository.ChargeRequestRepository;
import com.teamfp.aistock.domain.admin.dto.request.AdminSearchConditionDto;
import com.teamfp.aistock.domain.admin.dto.response.AdminActivityBalanceResponse;
import com.teamfp.aistock.domain.admin.dto.response.AdminActivityCategory;
import com.teamfp.aistock.domain.admin.dto.response.AdminActivityChargeResponse;
import com.teamfp.aistock.domain.admin.dto.response.AdminActivityInquiryResponse;
import com.teamfp.aistock.domain.admin.dto.response.AdminActivityMemberResponse;
import com.teamfp.aistock.domain.admin.dto.response.AdminActivityResponse;
import com.teamfp.aistock.domain.admin.dto.response.AdminActivityTradeResponse;
import com.teamfp.aistock.domain.admin.dto.response.AdminActivityType;
import com.teamfp.aistock.domain.admin.entity.AuditLog;
import com.teamfp.aistock.domain.admin.repository.AdminActivityRepository;
import com.teamfp.aistock.domain.admin.repository.AdminActivityRepository.ActivityKey;
import com.teamfp.aistock.domain.admin.repository.AuditLogRepository;
import com.teamfp.aistock.domain.inquiry.entity.Inquiry;
import com.teamfp.aistock.domain.inquiry.repository.InquiryRepository;
import com.teamfp.aistock.domain.order.entity.Order;
import com.teamfp.aistock.domain.order.repository.OrderRepository;
import com.teamfp.aistock.domain.user.entity.User;
import com.teamfp.aistock.domain.user.repository.UserRepository;
import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

/**
 * 관리자 "전체 활동 기록" 서버 페이지네이션(feat/admin-improvements). 탭은 회원이력·거래이력·충전차감이력·문의이력이고,
 * 한 가지 일은 한 줄(시작 시각 기준)로 그 결과까지 담는다 — 충전 요청은 요청·승인/반려·입금, 주문은 주문·체결·관리자
 * 강제 취소, 문의는 등록·답변. 회원 검색(아이디·이름·이메일·회원번호, 일치/포함)과 기간(시작일~종료일) 필터를 지원한다.
 *
 * AdminActivityRepository로 요청한 페이지의 키와 전체 개수를 구하고, 상세는 종류별로 IN 조회 한 번씩만 해서 채운다 —
 * 페이지 크기만큼만 읽으므로 몇 페이지를 보든 부하가 같다.
 */
@Service
@RequiredArgsConstructor
public class AdminActivityService {

    // 화면 탭 "전체" — category 파라미터 기본값
    public static final String CATEGORY_ALL = "ALL";
    static final String INVALID_CATEGORY_MESSAGE = "활동 탭(category)은 ALL, MEMBER, TRADE, CHARGE, INQUIRY 중 하나여야 합니다.";
    static final String INVALID_PERIOD_MESSAGE = "기간의 시작일이 종료일보다 늦을 수 없습니다.";

    private final AdminActivityRepository adminActivityRepository;
    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final OrderRepository orderRepository;
    private final ChargeRequestRepository chargeRequestRepository;
    private final AuditLogRepository auditLogRepository;
    private final AccountTransactionRepository accountTransactionRepository;
    private final InquiryRepository inquiryRepository;

    /**
     * @param category ALL(또는 미지정)이면 전체, 아니면 AdminActivityCategory 이름(대소문자 무시)
     * @param from     기간 시작일(그날 0시부터, 선택)
     * @param to       기간 종료일(그날 끝까지, 선택)
     */
    @Transactional(readOnly = true)
    public Page<AdminActivityResponse> getActivities(String category, AdminSearchConditionDto search, LocalDate from,
            LocalDate to, Pageable pageable) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new CustomException(ErrorCode.INVALID_INPUT, INVALID_PERIOD_MESSAGE);
        }
        List<AdminActivityType> types = typesOf(category);
        LocalDateTime fromAt = from == null ? null : from.atStartOfDay();
        LocalDateTime toAt = to == null ? null : to.plusDays(1).atStartOfDay();

        long total = adminActivityRepository.count(types, search, fromAt, toAt);
        List<ActivityKey> keys = adminActivityRepository.findKeys(types, search, fromAt, toAt, pageable.getOffset(),
                pageable.getPageSize());

        Details details = loadDetails(keys);
        List<AdminActivityResponse> content = keys.stream()
                .map(key -> toResponse(key, details))
                .filter(Objects::nonNull)
                .toList();
        return new PageImpl<>(content, pageable, total);
    }

    private List<AdminActivityType> typesOf(String category) {
        if (category == null || category.isBlank() || CATEGORY_ALL.equalsIgnoreCase(category.trim())) {
            return List.of(AdminActivityType.values());
        }
        AdminActivityCategory parsed;
        try {
            parsed = AdminActivityCategory.valueOf(category.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new CustomException(ErrorCode.INVALID_INPUT, INVALID_CATEGORY_MESSAGE);
        }
        return Arrays.stream(AdminActivityType.values()).filter(type -> type.category() == parsed).toList();
    }

    /** 한 페이지에 나온 키를 종류별로 모아, 상세에 필요한 엔티티를 종류마다 한 번씩만 조회한다. */
    private Details loadDetails(List<ActivityKey> keys) {
        Map<Long, AuditLog> auditLogs = byId(idsOf(keys, AdminActivityType.USER_STATUS, AdminActivityType.ACCOUNT_STATUS,
                AdminActivityType.ADMIN_CREATE), auditLogRepository::findAllById, AuditLog::getAuditLogId);
        List<Long> statusAccountIds = keys.stream()
                .filter(key -> key.type() == AdminActivityType.ACCOUNT_STATUS)
                .map(key -> auditLogs.get(key.id()))
                .filter(Objects::nonNull)
                .map(AuditLog::getTargetId)
                .distinct()
                .toList();
        Map<Long, Account> accounts = byId(statusAccountIds, accountRepository::findAllById, Account::getAccountId);

        List<Long> orderIds = idsOf(keys, AdminActivityType.TRADE);
        Map<Long, Order> orders = byId(orderIds, orderRepository::findAllWithUserByOrderIdIn, Order::getOrderId);
        // 같은 주문에 취소 기록이 여러 개면(이론상) 가장 최근 것을 쓴다.
        Map<Long, AuditLog> cancelLogs = orderIds.isEmpty() ? Map.of()
                : auditLogRepository.findAllByActionAndTargetTypeAndTargetIdIn(AuditLogService.ACTION_ORDER_CANCEL,
                                AuditLogService.TARGET_ORDER, orderIds).stream()
                        .collect(Collectors.toMap(AuditLog::getTargetId, Function.identity(),
                                (first, second) -> first.getCreatedAt().isAfter(second.getCreatedAt()) ? first : second));

        List<Long> requestIds = idsOf(keys, AdminActivityType.CHARGE_REQUEST);
        Map<Long, ChargeRequest> requests = byId(requestIds, chargeRequestRepository::findAllWithUserByRequestIdIn,
                ChargeRequest::getRequestId);
        Map<Long, AccountTransaction> deposits = requestIds.isEmpty() ? Map.of()
                : accountTransactionRepository.findAllByRelatedChargeRequestIdIn(requestIds).stream()
                        .collect(Collectors.toMap(AccountTransaction::getRelatedChargeRequestId, Function.identity(),
                                (first, second) -> first));

        Map<Long, AccountTransaction> balances = byId(idsOf(keys, AdminActivityType.SELF_BALANCE, AdminActivityType.ADMIN_BALANCE),
                accountTransactionRepository::findAllWithUserByTransactionIdIn, AccountTransaction::getTransactionId);
        List<Long> processorIds = balances.values().stream()
                .map(AccountTransaction::getProcessedBy)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, String> processorLoginIds = processorIds.isEmpty() ? Map.of()
                : userRepository.findAllById(processorIds).stream()
                        .filter(user -> user.getLoginId() != null)
                        .collect(Collectors.toMap(User::getUserId, User::getLoginId, (first, second) -> first));

        Map<Long, Inquiry> inquiries = byId(idsOf(keys, AdminActivityType.INQUIRY),
                inquiryRepository::findAllWithUserByInquiryIdIn, Inquiry::getInquiryId);
        return new Details(auditLogs, accounts, orders, cancelLogs, requests, deposits, balances, processorLoginIds, inquiries);
    }

    /** 키 조회와 상세 조회 사이에 행이 지워졌으면(예: 회원 탈퇴로 계좌·주문 삭제) 그 건만 건너뛴다(null). */
    private AdminActivityResponse toResponse(ActivityKey key, Details details) {
        return switch (key.type()) {
            case SIGNUP, WITHDRAWAL -> response(key, null, null, null, null, null);
            case USER_STATUS, ACCOUNT_STATUS, ADMIN_CREATE -> {
                AuditLog log = details.auditLogs().get(key.id());
                if (log == null) {
                    yield null;
                }
                Account account = key.type() == AdminActivityType.ACCOUNT_STATUS ? details.accounts().get(log.getTargetId()) : null;
                yield response(key, new AdminActivityMemberResponse(log.getBeforeValue(), log.getAfterValue(), log.getReason(),
                        log.getAdminLoginId(), account == null ? null : account.getAccountNumber()), null, null, null, null);
            }
            case TRADE -> {
                Order order = details.orders().get(key.id());
                if (order == null) {
                    yield null;
                }
                AuditLog cancelLog = details.cancelLogs().get(order.getOrderId());
                yield response(key, null, new AdminActivityTradeResponse(order.getOrderId(), order.getAccount().getAccountNumber(),
                        order.getStockCode(), order.getStockName(), order.getOrderType(), order.getPriceType(),
                        order.getQuantity(), order.getOrderPrice(), order.getExecPrice(),
                        order.getExecPrice() == null ? null : order.getExecPrice() * order.getQuantity(),
                        order.getStatus(), order.getExecutedAt(),
                        cancelLog == null ? null : cancelLog.getReason(),
                        cancelLog == null ? null : cancelLog.getAdminLoginId(),
                        cancelLog == null ? null : cancelLog.getCreatedAt()), null, null, null);
            }
            case CHARGE_REQUEST -> {
                ChargeRequest request = details.requests().get(key.id());
                if (request == null) {
                    yield null;
                }
                AccountTransaction deposit = details.deposits().get(request.getRequestId());
                yield response(key, null, null, new AdminActivityChargeResponse(request.getRequestId(),
                        request.getAccount().getAccountNumber(), request.getAmount(), request.getReason(), request.getStatus(),
                        request.getDecidedBy() == null ? null : request.getDecidedBy().getName(),
                        request.getDecisionReason(), request.getDecidedAt(),
                        deposit == null ? null : deposit.getAmount(),
                        deposit == null ? null : deposit.getBalanceAfter()), null, null);
            }
            case SELF_BALANCE, ADMIN_BALANCE -> {
                AccountTransaction transaction = details.balances().get(key.id());
                if (transaction == null) {
                    yield null;
                }
                yield response(key, null, null, null, new AdminActivityBalanceResponse(transaction.getTransactionId(),
                        transaction.getAccount().getAccountNumber(), transaction.getType(), transaction.getAmount(),
                        transaction.getBalanceAfter(), transaction.getReason(),
                        transaction.getProcessedBy() == null ? null : details.processorLoginIds().get(transaction.getProcessedBy())),
                        null);
            }
            case INQUIRY -> {
                Inquiry inquiry = details.inquiries().get(key.id());
                if (inquiry == null) {
                    yield null;
                }
                yield response(key, null, null, null, null, new AdminActivityInquiryResponse(inquiry.getInquiryId(),
                        inquiry.getTitle(), inquiry.getStatus(), inquiry.getAnswer(), inquiry.getAnsweredAt(),
                        inquiry.getAnsweredBy() == null ? null : inquiry.getAnsweredBy().getName()));
            }
        };
    }

    private AdminActivityResponse response(ActivityKey key, AdminActivityMemberResponse member, AdminActivityTradeResponse trade,
            AdminActivityChargeResponse charge, AdminActivityBalanceResponse balance, AdminActivityInquiryResponse inquiry) {
        return new AdminActivityResponse(key.type(), key.type().category(), key.id(), key.occurredAt(), key.userId(),
                key.userName(), key.loginId(), member, trade, charge, balance, inquiry);
    }

    private List<Long> idsOf(List<ActivityKey> keys, AdminActivityType... types) {
        List<AdminActivityType> wanted = List.of(types);
        return keys.stream().filter(key -> wanted.contains(key.type())).map(ActivityKey::id).distinct().toList();
    }

    private <T> Map<Long, T> byId(List<Long> ids, Function<List<Long>, List<T>> finder, Function<T, Long> idOf) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return finder.apply(ids).stream().collect(Collectors.toMap(idOf, Function.identity(), (first, second) -> first));
    }

    private record Details(
            Map<Long, AuditLog> auditLogs,
            Map<Long, Account> accounts,
            Map<Long, Order> orders,
            Map<Long, AuditLog> cancelLogs,
            Map<Long, ChargeRequest> requests,
            Map<Long, AccountTransaction> deposits,
            Map<Long, AccountTransaction> balances,
            Map<Long, String> processorLoginIds,
            Map<Long, Inquiry> inquiries
    ) {
    }
}
