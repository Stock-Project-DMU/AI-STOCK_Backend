package com.teamfp.aistock.domain.admin.dto.response;

import com.teamfp.aistock.domain.account.entity.Account;
import com.teamfp.aistock.domain.account.entity.AccountStatus;

/**
 * 관리자 — 계좌 상세 조회 응답 DTO(NAMING.md 8-16). 계좌 소유자를 바로 알아볼 수 있도록
 * userName을 함께 내려준다 — 마이페이지용 AccountInfoResponse는 userName이 없어 재사용하지 않는다.
 */
public record AdminAccountDetailResponse(
        Long accountId,
        String userName,
        String accountNumber,
        long balance,
        long frozenBalance,
        long baseBalance,
        AccountStatus status
) {

    public static AdminAccountDetailResponse from(Account account) {
        return new AdminAccountDetailResponse(
                account.getAccountId(),
                account.getUser().getName(),
                account.getAccountNumber(),
                account.getBalance(),
                account.getFrozenBalance(),
                account.getBaseBalance(),
                account.getStatus()
        );
    }
}
