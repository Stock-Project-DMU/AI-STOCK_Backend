package com.teamfp.aistock.domain.admin.dto.request;

import com.teamfp.aistock.domain.account.entity.AccountTransactionType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 관리자 계좌 잔고 수동 조정 요청 DTO(ADMIN_API_BACKEND_HANDOFF.md 4.3). type은
 * ADMIN_CHARGE(증액) 또는 ADMIN_DEDUCTION(감액)만 허용한다 — 그 외 유형은 주문·자동충전·초기지급처럼
 * 시스템이 자동으로 기록하는 것이라 관리자가 수동으로 만들 수 없다.
 */
public record AdminAccountAdjustmentRequest(
        @NotNull(message = "type은 필수 입력 값입니다.") AccountTransactionType type,
        @Positive(message = "조정 금액은 0보다 커야 합니다.") long amount,
        @NotBlank(message = "조정 사유는 필수 입력 값입니다.") String reason
) {
}
