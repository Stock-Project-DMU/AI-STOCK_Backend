package com.teamfp.aistock.domain.admin.dto.response;

import java.time.LocalDateTime;

import com.teamfp.aistock.domain.account.entity.ChargeRequest;
import com.teamfp.aistock.domain.account.entity.ChargeRequestStatus;

/**
 * 관리자용 충전 요청 조회 응답 DTO(ADMIN_API_BACKEND_HANDOFF.md 4.2). 사용자용
 * ChargeRequestResponse와 달리 요청자 식별 정보(loginId/userName)와 처리 관리자 이름까지 담는다.
 */
public record AdminChargeRequestResponse(
        Long requestId,
        Long accountId,
        String accountNumber,
        String loginId,
        String userName,
        long amount,
        String reason,
        ChargeRequestStatus status,
        String decidedByName,
        String decisionReason,
        LocalDateTime requestedAt,
        LocalDateTime decidedAt
) {

    public static AdminChargeRequestResponse from(ChargeRequest chargeRequest) {
        return new AdminChargeRequestResponse(
                chargeRequest.getRequestId(),
                chargeRequest.getAccount().getAccountId(),
                chargeRequest.getAccount().getAccountNumber(),
                chargeRequest.getAccount().getUser().getLoginId(),
                chargeRequest.getAccount().getUser().getName(),
                chargeRequest.getAmount(),
                chargeRequest.getReason(),
                chargeRequest.getStatus(),
                chargeRequest.getDecidedBy() == null ? null : chargeRequest.getDecidedBy().getName(),
                chargeRequest.getDecisionReason(),
                chargeRequest.getRequestedAt(),
                chargeRequest.getDecidedAt()
        );
    }
}
