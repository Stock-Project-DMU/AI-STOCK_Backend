package com.teamfp.aistock.domain.admin.dto.response;

import java.time.LocalDate;

import com.teamfp.aistock.domain.account.entity.Account;
import com.teamfp.aistock.domain.account.entity.AccountStatus;

/**
 * 관리자 계좌 목록 한 줄(feat/admin-improvements). 이전에는 목록·상세가 AdminAccountDetailResponse 하나를 같이
 * 썼는데, 상세에 보유 종목·수익률처럼 계좌마다 추가 조회가 필요한 정보를 넣으면서 목록은 이 DTO로 분리했다 —
 * 목록은 fetch join으로 이미 읽은 값만 담아 한 페이지를 쿼리 한 번(+count)으로 끝낸다.
 */
public record AdminAccountListResponse(
        Long accountId,
        Long userId,
        String loginId,
        String userName,
        String accountName,
        String accountNumber,
        long balance,
        long frozenBalance,
        long baseBalance,
        AccountStatus status,
        LocalDate openedAt
) {

    public static AdminAccountListResponse from(Account account) {
        return new AdminAccountListResponse(
                account.getAccountId(),
                account.getUser().getUserId(),
                account.getUser().getLoginId(),
                account.getUser().getName(),
                account.getAccountName(),
                account.getAccountNumber(),
                account.getBalance(),
                account.getFrozenBalance(),
                account.getBaseBalance(),
                account.getStatus(),
                account.getOpenedAt()
        );
    }
}
