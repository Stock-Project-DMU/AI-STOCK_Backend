package com.teamfp.aistock.domain.account.dto.response;

import com.teamfp.aistock.domain.account.entity.Account;
import com.teamfp.aistock.domain.account.entity.AccountStatus;

/**
 * 계좌 조회 응답 DTO. chargeCount를 함께 내려줘서 클라이언트가 "충전 몇 회 남았는지"를
 * 바로 계산해 보여줄 수 있게 한다(최대 3회 — AccountService.MAX_CHARGE_COUNT와 동일).
 */
public record AccountInfoResponse(
        Long accountId,
        String accountName,
        String accountNumber,
        long balance,
        long frozenBalance,
        long baseBalance,
        int chargeCount,
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
                account.getStatus()
        );
    }
}
