package com.teamfp.aistock.domain.account.dto.response;

import java.time.LocalDateTime;

import com.teamfp.aistock.domain.account.entity.AccountTransaction;
import com.teamfp.aistock.domain.account.entity.AccountTransactionType;

/**
 * 계좌 잔고 변동 원장 조회 응답 DTO(ADMIN_API_BACKEND_HANDOFF.md 4.3). 사용자·관리자 조회 API가
 * 동일한 필드를 쓴다 — 관리자만 볼 수 있는 별도 정보가 없어(처리 관리자 ID는 이미 processedBy로
 * 포함) DTO를 나누지 않는다.
 */
public record AccountTransactionResponse(
        Long transactionId,
        Long accountId,
        AccountTransactionType type,
        long amount,
        long balanceBefore,
        long balanceAfter,
        Long relatedOrderId,
        Long relatedChargeRequestId,
        Long processedBy,
        String reason,
        LocalDateTime createdAt
) {

    public static AccountTransactionResponse from(AccountTransaction transaction) {
        return new AccountTransactionResponse(
                transaction.getTransactionId(),
                transaction.getAccount().getAccountId(),
                transaction.getType(),
                transaction.getAmount(),
                transaction.getBalanceBefore(),
                transaction.getBalanceAfter(),
                transaction.getRelatedOrderId(),
                transaction.getRelatedChargeRequestId(),
                transaction.getProcessedBy(),
                transaction.getReason(),
                transaction.getCreatedAt()
        );
    }
}
