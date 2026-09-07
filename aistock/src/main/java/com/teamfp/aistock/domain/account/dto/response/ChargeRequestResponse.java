package com.teamfp.aistock.domain.account.dto.response;

import java.time.LocalDateTime;

import com.teamfp.aistock.domain.account.entity.ChargeRequest;
import com.teamfp.aistock.domain.account.entity.ChargeRequestStatus;

/**
 * 사용자용 충전 요청 조회 응답 DTO(ADMIN_API_BACKEND_HANDOFF.md 4.2). 처리 관리자 식별 정보는
 * 사용자에게 노출할 필요가 없어 담지 않는다(관리자용은 AdminChargeRequestResponse가 별도 담당).
 */
public record ChargeRequestResponse(
        Long requestId,
        Long accountId,
        long amount,
        String reason,
        ChargeRequestStatus status,
        String decisionReason,
        LocalDateTime requestedAt,
        LocalDateTime decidedAt
) {

    public static ChargeRequestResponse from(ChargeRequest chargeRequest) {
        return new ChargeRequestResponse(
                chargeRequest.getRequestId(),
                chargeRequest.getAccount().getAccountId(),
                chargeRequest.getAmount(),
                chargeRequest.getReason(),
                chargeRequest.getStatus(),
                chargeRequest.getDecisionReason(),
                chargeRequest.getRequestedAt(),
                chargeRequest.getDecidedAt()
        );
    }
}
