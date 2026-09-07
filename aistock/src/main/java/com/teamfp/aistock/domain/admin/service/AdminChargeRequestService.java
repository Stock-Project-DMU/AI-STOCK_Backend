package com.teamfp.aistock.domain.admin.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.teamfp.aistock.domain.account.entity.Account;
import com.teamfp.aistock.domain.account.entity.AccountTransactionType;
import com.teamfp.aistock.domain.account.entity.ChargeRequest;
import com.teamfp.aistock.domain.account.entity.ChargeRequestStatus;
import com.teamfp.aistock.domain.account.repository.ChargeRequestRepository;
import com.teamfp.aistock.domain.account.service.AccountTransactionService;
import com.teamfp.aistock.domain.admin.dto.request.AdminChargeDecisionRequest;
import com.teamfp.aistock.domain.admin.dto.response.AdminChargeRequestResponse;
import com.teamfp.aistock.domain.user.entity.User;
import com.teamfp.aistock.domain.user.repository.UserRepository;
import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

/**
 * 관리자 — 충전 요청 목록·상세 조회 및 승인·거절(ADMIN_API_BACKEND_HANDOFF.md 4.2). admin
 * 도메인은 자체 Entity/Repository를 두지 않고 account 도메인의 ChargeRequestRepository를 그대로
 * 주입받아 조합한다(CLAUDE.md 4번).
 */
@Service
@RequiredArgsConstructor
public class AdminChargeRequestService {

    private final ChargeRequestRepository chargeRequestRepository;
    private final UserRepository userRepository;
    private final AccountTransactionService accountTransactionService;
    private final AuditLogService auditLogService;

    @Transactional(readOnly = true)
    public Page<AdminChargeRequestResponse> getRequests(String query, ChargeRequestStatus status, Pageable pageable) {
        return chargeRequestRepository.searchWithAccountAndUser(blankToNull(query), status, pageable)
                .map(AdminChargeRequestResponse::from);
    }

    @Transactional(readOnly = true)
    public AdminChargeRequestResponse getRequestDetail(Long requestId) {
        return chargeRequestRepository.findWithAccountAndUserById(requestId)
                .map(AdminChargeRequestResponse::from)
                .orElseThrow(() -> new CustomException(ErrorCode.CHARGE_REQUEST_NOT_FOUND));
    }

    /**
     * 승인·거절 처리. findByIdForUpdate로 비관적 락을 걸어 동시에 들어온 두 번째 처리 요청이
     * "이미 처리됨"을 정확히 보고 막히게 한다(handoff 4.2 "승인·거절은 1회만 처리할 수 있어야
     * 한다" 요구사항). 승인 시 Account.applyAdminCharge()로 잔고를 올린다 — chargeBalance()와
     * 달리 자동충전 횟수(chargeCount)는 건드리지 않는다(Account.applyAdminCharge() Javadoc 참고).
     *
     * 승인·거절 금액의 일·월 누적 한도 검증은 handoff 문서가 "서버에서 검증한다"고 요구하지만
     * 구체적인 허용 범위가 정책 미확정이라 이번 범위에서는 넣지 않았다 — 한도가 정해지면 이
     * 메서드에 검증을 추가해야 한다.
     */
    @Transactional
    public AdminChargeRequestResponse decide(Long adminUserId, Long requestId, AdminChargeDecisionRequest request) {
        if (request.decision() == ChargeRequestStatus.PENDING) {
            throw new CustomException(ErrorCode.INVALID_INPUT);
        }

        ChargeRequest chargeRequest = chargeRequestRepository.findByIdForUpdate(requestId)
                .orElseThrow(() -> new CustomException(ErrorCode.CHARGE_REQUEST_NOT_FOUND));

        if (chargeRequest.getStatus() != ChargeRequestStatus.PENDING) {
            throw new CustomException(ErrorCode.CHARGE_REQUEST_ALREADY_PROCESSED);
        }

        User admin = userRepository.getReferenceById(adminUserId);

        if (request.decision() == ChargeRequestStatus.APPROVED) {
            Account account = chargeRequest.getAccount();
            long balanceBefore = account.getBalance();
            account.applyAdminCharge(chargeRequest.getAmount());
            accountTransactionService.record(account, AccountTransactionType.ADMIN_CHARGE, chargeRequest.getAmount(),
                    balanceBefore, null, chargeRequest.getRequestId(), adminUserId, request.reason());
            chargeRequest.approve(admin, request.reason());
        } else {
            chargeRequest.reject(admin, request.reason());
        }

        auditLogService.record(adminUserId, AuditLogService.ACTION_CHARGE_REQUEST_DECISION,
                AuditLogService.TARGET_CHARGE_REQUEST, requestId, ChargeRequestStatus.PENDING.name(),
                request.decision().name(), request.reason());

        return chargeRequestRepository.findWithAccountAndUserById(requestId)
                .map(AdminChargeRequestResponse::from)
                .orElseThrow(() -> new CustomException(ErrorCode.CHARGE_REQUEST_NOT_FOUND));
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
