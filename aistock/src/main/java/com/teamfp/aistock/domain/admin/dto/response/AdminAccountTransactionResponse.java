package com.teamfp.aistock.domain.admin.dto.response;

import java.time.LocalDateTime;

import com.teamfp.aistock.domain.account.entity.Account;
import com.teamfp.aistock.domain.account.entity.AccountTransaction;
import com.teamfp.aistock.domain.account.entity.AccountTransactionType;
import com.teamfp.aistock.domain.account.entity.ChargeRequest;
import com.teamfp.aistock.domain.user.entity.User;

/**
 * 관리자 "가상계좌 관리 → 충전·차감 이력" 한 줄(feat/admin-improvements). 처리가 끝난 충전·차감 한 건을 한 줄로
 * 보여준다 — 충전 요청에서 시작된 건이면 요청 내용·승인/거절 결과·입금 결과가 이 한 줄에 함께 담긴다.
 *
 * - entryType: TRANSACTION(잔고가 바뀐 기록) / REJECTED_REQUEST(거절된 충전 요청 — 잔고 변동 없음)
 * - type: AUTO_CHARGE(셀프 충전) / ADMIN_CHARGE(요청 승인·관리자 지급) / ADMIN_DEDUCTION(관리자 차감) /
 *   AUTO_DEDUCTION(관리자 계정 셀프 차감). 거절 건이면 null
 * - amount/balanceBefore/balanceAfter: 잔고 증감액(차감은 음수)과 처리 전후 잔고. 거절 건이면 null
 * - occurredAt: 잔고가 바뀐 시각, 거절 건이면 거절한 시각
 * - processedByLoginId: 관리자가 처리한 기록이면 그 관리자 아이디(셀프 충전이면 null)
 * - chargeRequest: 충전 요청에서 시작된 건(승인 입금·거절)이면 그 요청(요청 금액·사유·요청 시각·처리자·처리 사유)
 */
public record AdminAccountTransactionResponse(
        AdminChargeHistoryEntryType entryType,
        Long transactionId,
        Long accountId,
        String accountNumber,
        Long userId,
        String loginId,
        String userName,
        AccountTransactionType type,
        Long amount,
        Long balanceBefore,
        Long balanceAfter,
        Long processedBy,
        String processedByLoginId,
        String reason,
        LocalDateTime occurredAt,
        AdminChargeRequestResponse chargeRequest
) {

    public static AdminAccountTransactionResponse of(AccountTransaction transaction, String processedByLoginId,
            ChargeRequest chargeRequest) {
        Account account = transaction.getAccount();
        User user = account.getUser();
        return new AdminAccountTransactionResponse(
                AdminChargeHistoryEntryType.TRANSACTION,
                transaction.getTransactionId(),
                account.getAccountId(),
                account.getAccountNumber(),
                user.getUserId(),
                user.getLoginId(),
                user.getName(),
                transaction.getType(),
                transaction.getAmount(),
                transaction.getBalanceBefore(),
                transaction.getBalanceAfter(),
                transaction.getProcessedBy(),
                processedByLoginId,
                transaction.getReason(),
                transaction.getCreatedAt(),
                chargeRequest == null ? null : AdminChargeRequestResponse.from(chargeRequest)
        );
    }

    public static AdminAccountTransactionResponse rejected(ChargeRequest chargeRequest) {
        Account account = chargeRequest.getAccount();
        User user = account.getUser();
        return new AdminAccountTransactionResponse(
                AdminChargeHistoryEntryType.REJECTED_REQUEST,
                null,
                account.getAccountId(),
                account.getAccountNumber(),
                user.getUserId(),
                user.getLoginId(),
                user.getName(),
                null,
                null,
                null,
                null,
                chargeRequest.getDecidedBy() == null ? null : chargeRequest.getDecidedBy().getUserId(),
                chargeRequest.getDecidedBy() == null ? null : chargeRequest.getDecidedBy().getLoginId(),
                chargeRequest.getDecisionReason(),
                chargeRequest.getDecidedAt() == null ? chargeRequest.getRequestedAt() : chargeRequest.getDecidedAt(),
                AdminChargeRequestResponse.from(chargeRequest)
        );
    }
}
