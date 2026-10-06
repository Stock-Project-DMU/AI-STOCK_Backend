package com.teamfp.aistock.domain.account.dto.request;

import jakarta.validation.constraints.Positive;

/**
 * 사용자 직접 충전 요청 DTO(POST /api/accounts/{accountId}/charge). 금액은 사용자가 자유롭게
 * 입력하고(1회 최대 1억원), 계좌당 Account.MAX_CHARGE_COUNT회까지만 관리자 승인 없이 바로 충전된다.
 * 상한이 없으면 승인 없이 사실상 무제한 충전이 되고, 아주 큰 값은 잔고 long 연산이 넘쳐 음수가 될 수
 * 있어 막는다(코드리뷰 반영). 1회 1억원 상한은 관리자 계좌에는 적용하지 않아야 해서(feat/admin-improvements)
 * @Max가 아니라 AccountService.chargeBalance()에서 계좌 주인의 권한을 보고 검사한다.
 */
public record ChargeBalanceRequest(
        @Positive(message = "충전 금액은 0보다 커야 합니다.")
        long amount
) {

    public static final long MAX_CHARGE_AMOUNT = 100_000_000L;
    public static final String MAX_CHARGE_AMOUNT_MESSAGE = "1회 충전 금액은 최대 1억원입니다.";
}
