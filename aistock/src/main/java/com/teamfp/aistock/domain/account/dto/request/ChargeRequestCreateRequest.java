package com.teamfp.aistock.domain.account.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * 추가 충전 요청 생성 DTO(ADMIN_API_BACKEND_HANDOFF.md 4.2).
 */
public record ChargeRequestCreateRequest(
        @Positive(message = "충전 금액은 0보다 커야 합니다.") long amount,
        @NotBlank(message = "요청 사유는 필수 입력 값입니다.") String reason
) {
}
