package com.teamfp.aistock.domain.account.dto.response;

import java.time.LocalDateTime;

import com.teamfp.aistock.domain.account.entity.AccountTransaction;
import com.teamfp.aistock.domain.account.entity.AccountTransactionType;

/**
 * 계좌 잔고 변동 원장 조회 응답 DTO(관리자 API — 충전·잔액 변동 원장). 사용자·관리자 조회 API가
 * 동일한 필드를 쓴다 — DTO는 나누지 않고, 사용자 조회는 forUser()로 관리자 지급·차감 사유만 고정 문구로 바꾼다.
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
        return of(transaction, transaction.getReason());
    }

    /**
     * 사용자 본인 계좌 내역용 — 관리자 지급·차감은 관리자가 적은 사유 대신 고정 문구("관리자 지급"/"관리자 차감")를
     * 보여준다(관리자 내부 메모가 사용자에게 그대로 노출되지 않게, 2026-10-13). 관리자 API는 from()으로 원래 사유를 본다.
     */
    public static AccountTransactionResponse forUser(AccountTransaction transaction) {
        String reason = switch (transaction.getType()) {
            case ADMIN_CHARGE -> ADMIN_CHARGE_LABEL;
            case ADMIN_DEDUCTION -> ADMIN_DEDUCTION_LABEL;
            default -> transaction.getReason();
        };
        return of(transaction, reason);
    }

    private static final String ADMIN_CHARGE_LABEL = "관리자 지급";
    private static final String ADMIN_DEDUCTION_LABEL = "관리자 차감";

    private static AccountTransactionResponse of(AccountTransaction transaction, String reason) {
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
                reason,
                transaction.getCreatedAt()
        );
    }
}
