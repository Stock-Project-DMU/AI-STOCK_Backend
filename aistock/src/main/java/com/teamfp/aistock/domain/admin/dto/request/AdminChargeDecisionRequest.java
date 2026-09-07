package com.teamfp.aistock.domain.admin.dto.request;

import com.teamfp.aistock.domain.account.entity.ChargeRequestStatus;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 관리자 충전 요청 승인·거절 요청 DTO(ADMIN_API_BACKEND_HANDOFF.md 4.2). decision은 APPROVED
 * 또는 REJECTED만 허용한다 — PENDING을 다시 보내는 것은 의미가 없어 서비스 레이어에서 막는다.
 */
public record AdminChargeDecisionRequest(
        @NotNull(message = "decision은 필수 입력 값입니다.") ChargeRequestStatus decision,
        @NotBlank(message = "처리 사유는 필수 입력 값입니다.") String reason
) {
}
