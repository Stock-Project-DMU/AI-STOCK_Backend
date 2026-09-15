package com.teamfp.aistock.domain.admin.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.teamfp.aistock.domain.account.entity.Account;
import com.teamfp.aistock.domain.account.entity.AccountStatus;
import com.teamfp.aistock.domain.account.entity.AccountTransactionType;
import com.teamfp.aistock.domain.account.repository.AccountRepository;
import com.teamfp.aistock.domain.account.dto.response.AccountTransactionResponse;
import com.teamfp.aistock.domain.account.service.AccountTransactionService;
import com.teamfp.aistock.domain.admin.dto.request.AdminAccountAdjustmentRequest;
import com.teamfp.aistock.domain.admin.dto.request.AdminAccountStatusRequest;
import com.teamfp.aistock.domain.admin.dto.response.AdminAccountDetailResponse;
import com.teamfp.aistock.domain.order.service.OrderService;
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

    @Transactional(readOnly = true)
    public AdminAccountDetailResponse getAccountDetail(Long accountId) {
        return AdminAccountDetailResponse.from(findAccount(accountId));
    }

    // 계좌 목록·검색(ADMIN_API_BACKEND_HANDOFF.md 3.4). 별도 목록 DTO를 새로 만들지 않고
    // AdminAccountDetailResponse를 그대로 재사용한다 — admin-trade(8-15)가 이미 목록/상세를
    // 같은 DTO로 통일한 것과 동일한 이유로, 계좌 한 건이 목록/상세에서 내려주는 필드가 같다.
    @Transactional(readOnly = true)
    public Page<AdminAccountDetailResponse> getAccounts(String query, AccountStatus status, Pageable pageable) {
        return accountRepository.searchAccountsWithUser(blankToNull(query), status, pageable)
                .map(AdminAccountDetailResponse::from);
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
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
        return AdminAccountDetailResponse.from(account);
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

        return AdminAccountDetailResponse.from(account);
    }

    private Account findAccountForUpdate(Long accountId) {
        return accountRepository.findAccountWithUserByIdForUpdate(accountId)
                .orElseThrow(() -> new CustomException(ErrorCode.ACCOUNT_NOT_FOUND));
    }
}
