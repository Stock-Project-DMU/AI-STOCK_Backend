package com.teamfp.aistock.domain.account.dto.request;

import jakarta.validation.constraints.Positive;

/**
 * 관리자 계정 본인 계좌 직접 차감 요청 DTO(POST /api/accounts/{accountId}/deduct, feat/admin-improvements).
 * 관리자가 서비스 화면의 "가상계좌 관리"에서 충전한 가상캐시를 다시 빼는 용도다. 차감 가능 금액 상한은
 * 계좌 상태에 따라 달라져 @Max가 아니라 AccountService.deductBalance()에서 검사한다.
 */
public record DeductBalanceRequest(
        @Positive(message = "차감 금액은 0보다 커야 합니다.")
        long amount
) {
}
