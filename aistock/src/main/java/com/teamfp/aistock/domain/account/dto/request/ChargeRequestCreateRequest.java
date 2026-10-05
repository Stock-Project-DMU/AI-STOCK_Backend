package com.teamfp.aistock.domain.account.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 추가 충전 요청 생성 DTO(ADMIN_API_BACKEND_HANDOFF.md 4.2). 직접 충전 3회를 다 쓴 뒤 관리자에게 요청하는
 * 금액은 1회 최대 1조원이다(계좌 예치금 한도와 같은 값).
 */
public record ChargeRequestCreateRequest(
        @Positive(message = "충전 금액은 0보다 커야 합니다.")
        @Max(value = ChargeRequestCreateRequest.MAX_REQUEST_AMOUNT, message = "1회 충전 요청 금액은 최대 1조원입니다.")
        long amount,
        @NotBlank(message = "요청 사유는 필수 입력 값입니다.")
        @Size(max = 500, message = "요청 사유는 500자를 넘을 수 없습니다.")
        String reason
) {

    public static final long MAX_REQUEST_AMOUNT = 1_000_000_000_000L;
}
