package com.teamfp.aistock.domain.admin.dto.response;

import java.time.LocalDateTime;

import com.teamfp.aistock.domain.account.entity.ChargeRequestStatus;

/**
 * 전체 활동 기록 — 충전 요청 한 줄의 상세(feat/admin-improvements). 요청 → 승인/반려 → 입금이 한 줄에 담긴다.
 * decided*는 처리 끝난 요청, depositedAmount/balanceAfter는 승인돼 실제로 입금된 요청일 때만 채워진다.
 */
public record AdminActivityChargeResponse(
        Long requestId,
        String accountNumber,
        long requestedAmount,
        String reason,
        ChargeRequestStatus status,
        String decidedByName,
        String decisionReason,
        LocalDateTime decidedAt,
        Long depositedAmount,
        Long balanceAfter
) {
}
