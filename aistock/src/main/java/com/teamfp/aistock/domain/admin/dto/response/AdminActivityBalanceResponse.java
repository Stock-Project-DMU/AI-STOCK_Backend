package com.teamfp.aistock.domain.admin.dto.response;

import com.teamfp.aistock.domain.account.entity.AccountTransactionType;

/**
 * 전체 활동 기록 — 셀프 충전·차감(SELF_BALANCE)과 관리자 지급·차감(ADMIN_BALANCE) 한 줄의 상세(feat/admin-improvements).
 * amount는 증감액(차감은 음수), processedByLoginId는 관리자 지급·차감일 때 처리한 관리자 아이디다.
 */
public record AdminActivityBalanceResponse(
        Long transactionId,
        String accountNumber,
        AccountTransactionType type,
        long amount,
        long balanceAfter,
        String reason,
        String processedByLoginId
) {
}
