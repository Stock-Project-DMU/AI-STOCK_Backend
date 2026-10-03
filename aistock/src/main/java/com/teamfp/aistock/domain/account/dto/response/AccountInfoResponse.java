package com.teamfp.aistock.domain.account.dto.response;

import java.math.BigDecimal;

import com.teamfp.aistock.domain.account.entity.Account;
import com.teamfp.aistock.domain.account.entity.AccountStatus;

/**
 * 계좌 조회 응답 DTO. chargeCount/maxChargeCount를 함께 내려줘서 클라이언트가 "충전 몇 회
 * 남았는지"를 바로 계산해 보여줄 수 있게 한다(chargeCount < maxChargeCount면 직접 충전,
 * 아니면 관리자 충전 요청). interestRate는 예치금 연이율(%, 예: 0.50), totalInterest는 지금까지 받은
 * 예치금 이자 누계(원)다.
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
                account.getInterestRate(),
                account.getTotalInterest(),
                account.getStatus()
        );
    }
}
