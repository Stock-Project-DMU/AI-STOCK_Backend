package com.teamfp.aistock.domain.admin.service;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.teamfp.aistock.domain.account.dto.response.AccountTransactionResponse;
import com.teamfp.aistock.domain.account.dto.response.ProfitResponse;
import com.teamfp.aistock.domain.account.entity.Account;
import com.teamfp.aistock.domain.account.entity.AccountStatus;
import com.teamfp.aistock.domain.account.entity.AccountTransactionType;
import com.teamfp.aistock.domain.account.repository.AccountRepository;
import com.teamfp.aistock.domain.account.repository.AccountTransactionRepository;
import com.teamfp.aistock.domain.account.repository.ChargeRequestRepository;
import com.teamfp.aistock.domain.account.service.AccountTransactionService;
import com.teamfp.aistock.domain.admin.dto.request.AdminAccountAdjustmentRequest;
import com.teamfp.aistock.domain.admin.dto.request.AdminAccountStatusRequest;
import com.teamfp.aistock.domain.admin.dto.request.AdminSearchConditionDto;
import com.teamfp.aistock.domain.admin.dto.response.AdminAccountDetailResponse;
import com.teamfp.aistock.domain.admin.dto.response.AdminAccountListResponse;
import com.teamfp.aistock.domain.admin.dto.response.AdminAccountStatsResponse;
import com.teamfp.aistock.domain.admin.dto.response.AdminChargeRequestResponse;
import com.teamfp.aistock.domain.admin.dto.response.AuditLogResponse;
import com.teamfp.aistock.domain.admin.repository.AuditLogRepository;
import com.teamfp.aistock.domain.notification.entity.NotificationType;
import com.teamfp.aistock.domain.notification.service.NotificationService;
import com.teamfp.aistock.domain.order.dto.HoldingValuationDto;
import com.teamfp.aistock.domain.order.dto.response.HoldingResponse;
import com.teamfp.aistock.domain.order.dto.response.OrderHistoryResponse;
import com.teamfp.aistock.domain.order.repository.OrderRepository;
import com.teamfp.aistock.domain.order.service.HoldingValuationService;
import com.teamfp.aistock.domain.order.service.OrderService;
import com.teamfp.aistock.domain.order.service.RealizedReturnService;
import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

/**
 * 관리자 — 계좌 상세 조회 및 거래 정지상태 변경. admin 도메인은 자체 Entity/Repository를 두지
 * 않고 account 도메인의 AccountRepository를 그대로 주입받아 조합한다(CLAUDE.md 4번).
 * accounts.status는 로그인은 그대로 두고 매수·매도 주문만 막는 개념이라(CLAUDE.md 8번),
 * AdminUserService.suspend()의 본인/마지막 관리자 lockout 가드가 필요 없다 — 관리자 계정
 * 자체는 users.status로 별도 관리되므로 계좌 정지로는 관리자 접근이 막히지 않는다.
 *
 * 정지(SUSPENDED) 처리 시 order 도메인의 OrderService.cancelAllPendingOrdersForSuspension()을
 * 함께 호출한다 — Repository 직접 조작 대신 서비스 계층을 거치는 이유는 CLAUDE.md 4번
 * "도메인 간 직접 참조 대신 서비스 계층을 통해 호출" 원칙에 따라, PENDING 주문 취소가 갖는
 * 잔고 동결 해제·Redis pending:orders 정리 같은 order 도메인 고유 로직을 이 서비스에서
 * 중복 구현하지 않기 위함이다(코드리뷰 반영).
 */
@Service
@RequiredArgsConstructor
public class AdminAccountService {

    private final AccountRepository accountRepository;
    private final OrderService orderService;
    private final AccountTransactionService accountTransactionService;
    private final AuditLogService auditLogService;
    private final NotificationService notificationService;
    // 계좌 상세의 보유 종목·총 자산·수익률 계산용(feat/admin-improvements).
    private final HoldingValuationService holdingValuationService;
    // 계좌 상세의 최근 주문·충전 요청 이력(feat/admin-improvements).
    private final OrderRepository orderRepository;
    private final ChargeRequestRepository chargeRequestRepository;
    // 계좌 상세의 정지 이력·누적 통계(feat/admin-improvements)
    private final AuditLogRepository auditLogRepository;
    private final AccountTransactionRepository accountTransactionRepository;

    // 계좌 상세에 함께 내려주는 최근 주문·충전 요청 건수(회원 상세와 같은 값).
    static final int RECENT_ORDER_LIMIT = 20;
    static final int RECENT_CHARGE_REQUEST_LIMIT = 10;

    @Transactional(readOnly = true)
    public AdminAccountDetailResponse getAccountDetail(Long accountId) {
        return toDetail(findAccount(accountId));
    }

    // 계좌 목록·검색(ADMIN_API_BACKEND_HANDOFF.md 3.4). 원래는 AdminAccountDetailResponse를 목록·상세가 같이
    // 썼는데, 상세에 보유 종목·수익률을 넣으면서(feat/admin-improvements) 목록은 계좌마다 추가 조회가 없는
    // AdminAccountListResponse로 분리했다.
    @Transactional(readOnly = true)
    public Page<AdminAccountListResponse> getAccounts(AdminSearchConditionDto search, AccountStatus status, Pageable pageable) {
        return accountRepository.searchAccountsWithUser(search.query(), search.pattern(), search.queryId(), search.field(),
                        search.exact(), status, pageable)
                .map(AdminAccountListResponse::from);
    }

    // 계좌 상세 = 계좌 필드 + 보유 종목 + 총 자산·수익률(마이페이지와 같은 ProfitResponse.calculate() 식).
    private AdminAccountDetailResponse toDetail(Account account) {
        Long accountId = account.getAccountId();
        List<HoldingValuationDto> valuations = holdingValuationService.getHoldingValuations(accountId);
        List<HoldingResponse> holdings = valuations.stream().map(HoldingResponse::of).toList();
        // 주문은 최근 N건 + 전체 건수(회원 상세와 같은 방식), 충전 요청은 최근 N건. 잔고 변동 내역은 건수가 많아
        // 상세에 넣지 않고 기존 페이지 API(GET .../{accountId}/transactions)로 따로 본다.
        List<OrderHistoryResponse> recentOrders = orderRepository
                .findRecentByAccountIdIn(List.of(accountId), PageRequest.of(0, RECENT_ORDER_LIMIT)).stream()
                .map(OrderHistoryResponse::from)
                .toList();
        long orderCount = orderRepository.countByAccountIdIn(List.of(accountId));
        List<AdminChargeRequestResponse> recentChargeRequests = chargeRequestRepository
                .findAllByAccountId(accountId, PageRequest.of(0, RECENT_CHARGE_REQUEST_LIMIT)).stream()
                .map(AdminChargeRequestResponse::from)
                .toList();
        List<AuditLogResponse> suspensionHistory = auditLogRepository
                .findAllByActionAndTargetTypeAndTargetIdOrderByCreatedAtDesc(AuditLogService.ACTION_ACCOUNT_STATUS_CHANGE,
                        AuditLogService.TARGET_ACCOUNT, accountId).stream()
                .map(AuditLogResponse::from)
                .toList();
        return AdminAccountDetailResponse.of(account, holdings, ProfitResponse.calculate(account, valuations),
                recentOrders, orderCount, recentChargeRequests, suspensionHistory, statsOf(accountId));
    }

    // 계좌 누적 통계 — 잔고 내역 유형별 합계(차감·수수료는 음수로 쌓여 있어 양수로 바꾼다)와 실현 손익 합계.
    private AdminAccountStatsResponse statsOf(Long accountId) {
        long totalCharged = accountTransactionRepository.sumAmountByAccountIdAndTypeIn(accountId,
                List.of(AccountTransactionType.AUTO_CHARGE, AccountTransactionType.ADMIN_CHARGE));
        long totalDeducted = -accountTransactionRepository.sumAmountByAccountIdAndTypeIn(accountId,
                List.of(AccountTransactionType.ADMIN_DEDUCTION, AccountTransactionType.AUTO_DEDUCTION));
        long totalFee = -accountTransactionRepository.sumAmountByAccountIdAndTypeIn(accountId,
                List.of(AccountTransactionType.TRADE_FEE));
        return new AdminAccountStatsResponse(totalCharged, totalDeducted, totalFee, realizedProfitOf(List.of(accountId)));
    }

    // 체결 기록이 맞지 않아 실현 손익을 계산할 수 없으면(예: 테스트로 직접 넣은 데이터) 상세 조회를 막지 않고 null.
    private Long realizedProfitOf(List<Long> accountIds) {
        List<OrderHistoryResponse> executedOrders = orderRepository.findExecutedByAccountIdIn(accountIds).stream()
                .map(OrderHistoryResponse::from)
                .toList();
        try {
            return RealizedReturnService.sumRealizedProfit(executedOrders);
        } catch (CustomException e) {
            return null;
        }
    }


    @Transactional
    public AdminAccountDetailResponse updateAccountStatus(Long adminUserId, Long accountId, AdminAccountStatusRequest request) {
        Account account = findAccount(accountId);
        AccountStatus beforeStatus = account.getStatus();
        if (request.status() == AccountStatus.SUSPENDED) {
            account.suspend();
            // 정지 시점에 남아있는 PENDING 지정가 주문을 그대로 두면 tick 체결이나 사용자의
            // 자력 취소로 정지 정책이 깨질 수 있어(OrderService.cancelAllPendingOrdersForSuspension
            // Javadoc 참고), 정지와 같은 트랜잭션에서 함께 취소한다.
            orderService.cancelAllPendingOrdersForSuspension(account);
        } else {
            account.activate();
        }
        auditLogService.record(adminUserId, AuditLogService.ACTION_ACCOUNT_STATUS_CHANGE, AuditLogService.TARGET_ACCOUNT,
                accountId, beforeStatus.name(), account.getStatus().name(), request.reason());
        if (beforeStatus != account.getStatus()) {
            String title = account.getStatus() == AccountStatus.SUSPENDED ? "계좌 거래 정지" : "계좌 거래 재개";
            notificationService.notify(account.getUser().getUserId(), NotificationType.ACCOUNT, title,
                    String.format("%s 계좌의 거래 상태가 변경되었습니다. 사유: %s", account.getAccountName(), request.reason()));
        }
        return toDetail(account);
    }

    private Account findAccount(Long accountId) {
        return accountRepository.findAccountWithUserById(accountId)
                .orElseThrow(() -> new CustomException(ErrorCode.ACCOUNT_NOT_FOUND));
    }

    // 잔고 변동 원장 조회(4.3).
    @Transactional(readOnly = true)
    public Page<AccountTransactionResponse> getTransactions(Long accountId, Pageable pageable) {
        findAccount(accountId); // 존재하지 않는 계좌면 ACCOUNT_NOT_FOUND로 막는다.
        return accountTransactionService.getTransactions(accountId, pageable);
    }

    /**
     * 관리자 수동 잔고 조정(4.3 POST .../adjustments). type이 ADMIN_CHARGE면 증액,
     * ADMIN_DEDUCTION이면 감액한다 — Account.applyAdminCharge()/applyAdminDeduction() Javadoc
     * 참고. 감액 시 balance가 음수가 되는 것은 막는다(가상캐시라도 마이너스 잔고는 의미가 없다).
     *
     * 계좌를 findAccountWithUserByIdForUpdate()로 비관적 락을 걸어 조회한다(코드리뷰 반영,
     * 2026-09) — 동시에 체결되는 주문과의 낙관적 락 충돌을 줄인다.
     *
     * balance뿐 아니라 baseBalance도 음수가 되지 않는지 함께 검증한다 — baseBalance는 수익률
     * 계산식(총자산-baseBalance)/baseBalance의 분모라서, 음수가 되면 실제로는 이득인데도
     * 수익률 부호가 뒤집혀 표시되는 문제가 있었다(코드리뷰 반영).
     *
     * 잔고 변경 자체(AccountTransactionService)와 별개로, "관리자가 언제 왜 이 조정을
     * 했는지"는 감사 로그(AuditLogService)에도 남긴다 — 다른 관리자 작업(계좌 정지 등)과
     * 동일하게 처음부터 빠져 있던 부분이라 이번에 추가했다(코드리뷰 반영).
     */
    @Transactional
    public AdminAccountDetailResponse adjustBalance(Long adminUserId, Long accountId, AdminAccountAdjustmentRequest request) {
        Account account = findAccountForUpdate(accountId);
        long balanceBefore = account.getBalance();

        if (request.type() == AccountTransactionType.ADMIN_DEDUCTION) {
            if (account.getBalance() < request.amount() || account.getBaseBalance() < request.amount()) {
                throw new CustomException(ErrorCode.INSUFFICIENT_BALANCE);
            }
            account.applyAdminDeduction(request.amount());
            accountTransactionService.record(account, AccountTransactionType.ADMIN_DEDUCTION, -request.amount(),
                    balanceBefore, null, null, adminUserId, request.reason());
        } else if (request.type() == AccountTransactionType.ADMIN_CHARGE) {
            if (!account.canDeposit(request.amount())) {
                throw new CustomException(ErrorCode.DEPOSIT_LIMIT_EXCEEDED);
            }
            account.applyAdminCharge(request.amount());
            accountTransactionService.record(account, AccountTransactionType.ADMIN_CHARGE, request.amount(),
                    balanceBefore, null, null, adminUserId, request.reason());
        } else {
            // ADMIN_CHARGE/ADMIN_DEDUCTION 외 다른 유형은 시스템이 자동 기록하는 것이라
            // 관리자가 수동으로 만들 수 없다(AdminAccountAdjustmentRequest Javadoc 참고).
            throw new CustomException(ErrorCode.INVALID_INPUT);
        }

        auditLogService.record(adminUserId, AuditLogService.ACTION_ACCOUNT_ADJUSTMENT, AuditLogService.TARGET_ACCOUNT,
                accountId, String.valueOf(balanceBefore), String.valueOf(account.getBalance()), request.reason());

        String title = request.type() == AccountTransactionType.ADMIN_CHARGE ? "관리자 잔고 증액" : "관리자 잔고 감액";
        notificationService.notify(account.getUser().getUserId(), NotificationType.ACCOUNT, title,
                String.format("%s 계좌 잔고가 %,d원 조정되었습니다. 사유: %s", account.getAccountName(),
                        request.amount(), request.reason()));

        return toDetail(account);
    }

    private Account findAccountForUpdate(Long accountId) {
        return accountRepository.findAccountWithUserByIdForUpdate(accountId)
                .orElseThrow(() -> new CustomException(ErrorCode.ACCOUNT_NOT_FOUND));
    }
}
