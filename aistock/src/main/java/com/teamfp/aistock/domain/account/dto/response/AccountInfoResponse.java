package com.teamfp.aistock.domain.account.dto.response;

import java.math.BigDecimal;

import com.teamfp.aistock.domain.account.dto.request.ChargeBalanceRequest;
import com.teamfp.aistock.domain.account.dto.request.ChargeRequestCreateRequest;
import com.teamfp.aistock.domain.account.entity.Account;
import com.teamfp.aistock.domain.account.entity.AccountStatus;

/**
 * 계좌 조회 응답 DTO. chargeCount/maxChargeCount를 함께 내려줘서 클라이언트가 "충전 몇 회
 * 남았는지"를 바로 계산해 보여줄 수 있게 한다(chargeCount < maxChargeCount면 직접 충전,
 * 아니면 관리자 충전 요청). interestRate는 예치금 연이율(%, 예: 0.50), totalInterest는 지금까지 받은
 * 예치금 이자 누계(원)다. unlimitedCharge가 true면 관리자 계정의 계좌라 셀프 충전 횟수·1회 금액 제한이
 * 없고 예치금 한도가 999조원이다 — 클라이언트는 남은 충전 횟수와 충전 요청 버튼을 숨긴다(feat/admin-improvements).
 * chargeableAmount는 지금 한 번에 충전(또는 충전 요청)할 수 있는 최대 금액(원)으로, 충전 화면의 "최대" 버튼이
 * 그대로 입력칸에 채우는 값이다(chargeableAmountOf 참고). deductibleAmount는 관리자 계좌의 직접 차감
 * (POST .../deduct)으로 지금 뺄 수 있는 최대 금액(원)이고, 차감할 수 없는 일반 사용자 계좌는 0이다.
 */
public record AccountInfoResponse(
        Long accountId,
        String accountName,
        String accountNumber,
        long balance,
        long frozenBalance,
        long baseBalance,
        int chargeCount,
        int maxChargeCount,
        boolean unlimitedCharge,
        long chargeableAmount,
        long deductibleAmount,
        BigDecimal interestRate,
        long totalInterest,
        AccountStatus status
) {

    public static AccountInfoResponse from(Account account) {
        return new AccountInfoResponse(
                account.getAccountId(),
                account.getAccountName(),
                account.getAccountNumber(),
                account.getBalance(),
                account.getFrozenBalance(),
                account.getBaseBalance(),
                account.getChargeCount(),
                Account.MAX_CHARGE_COUNT,
                account.isChargeUnlimited(),
                chargeableAmountOf(account),
                account.isChargeUnlimited() ? account.getDeductibleAmount() : 0L,
                account.getInterestRate(),
                account.getTotalInterest(),
                account.getStatus()
        );
    }

    /**
     * 지금 한 번에 넣을 수 있는 최대 금액 — 예치금 한도까지 남은 금액(Account.getRemainingDepositAmount)을
     * 이번 충전 방식의 1회 상한으로 한 번 더 자른다.
     * - 관리자 계좌: 1회 상한이 없어 999조원까지 남은 금액 그대로
     * - 일반 사용자, 직접 충전 횟수 남음: 1회 1억원(ChargeBalanceRequest.MAX_CHARGE_AMOUNT)
     * - 일반 사용자, 직접 충전 3회 소진: 충전 요청 1회 1조원(ChargeRequestCreateRequest.MAX_REQUEST_AMOUNT)
     * 정지(SUSPENDED) 계좌는 직접 충전·충전 요청 모두 막히므로 0이다.
     */
    private static long chargeableAmountOf(Account account) {
        if (account.getStatus() == AccountStatus.SUSPENDED) {
            return 0L;
        }
        long remaining = account.getRemainingDepositAmount();
        if (account.isChargeUnlimited()) {
            return remaining;
        }
        long perChargeMax = account.hasRemainingChargeCount()
                ? ChargeBalanceRequest.MAX_CHARGE_AMOUNT
                : ChargeRequestCreateRequest.MAX_REQUEST_AMOUNT;
        return Math.min(remaining, perChargeMax);
    }
}
