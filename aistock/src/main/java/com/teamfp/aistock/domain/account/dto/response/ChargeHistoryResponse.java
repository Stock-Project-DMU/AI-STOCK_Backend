package com.teamfp.aistock.domain.account.dto.response;

import java.time.LocalDateTime;

import com.teamfp.aistock.domain.account.dto.ChargeSource;
import com.teamfp.aistock.domain.account.entity.AccountTransaction;
import com.teamfp.aistock.domain.account.entity.ChargeRequest;
import com.teamfp.aistock.domain.account.entity.ChargeRequestStatus;

/**
 * 충전 이력 한 건(GET /api/accounts/{accountId}/charge-history). 직접 충전(원장 AUTO_CHARGE), 관리자
 * 충전 요청(charge_requests — 대기·승인·거절), 관리자 수동 지급(원장 ADMIN_CHARGE 중 요청과 무관한 것)을
 * 한 목록으로 합쳐, 화면이 source로 "셀프/관리자"를 구분해 보여줄 수 있게 한다.
 *
 * @param balanceAfter 충전이 실제 반영된 뒤 잔고. 대기·거절 요청은 반영 전이라 null
 * @param requestId    관리자 충전 요청 ID. 직접 충전·관리자 수동 지급은 null
 */
public record ChargeHistoryResponse(
        ChargeSource source,
        ChargeRequestStatus status,
        long amount,
        Long balanceAfter,
        String reason,
        String decisionReason,
        LocalDateTime requestedAt,
        LocalDateTime decidedAt,
        Long requestId
) {

    /** 직접 충전 — 요청 즉시 반영되므로 항상 APPROVED. */
    public static ChargeHistoryResponse fromSelfCharge(AccountTransaction transaction) {
        return new ChargeHistoryResponse(ChargeSource.SELF, ChargeRequestStatus.APPROVED, transaction.getAmount(),
                transaction.getBalanceAfter(), transaction.getReason(), null,
                transaction.getCreatedAt(), transaction.getCreatedAt(), null);
    }

    /** 관리자 수동 지급(충전 요청 없이 관리자가 잔고 조정으로 증액) — 사유는 관리자가 남긴 조정 사유. */
    public static ChargeHistoryResponse fromAdminGrant(AccountTransaction transaction) {
        return new ChargeHistoryResponse(ChargeSource.ADMIN, ChargeRequestStatus.APPROVED, transaction.getAmount(),
                transaction.getBalanceAfter(), "관리자 지급", transaction.getReason(),
                transaction.getCreatedAt(), transaction.getCreatedAt(), null);
    }

    /**
     * 관리자 충전 요청. 승인된 요청은 승인 시점 원장(ADMIN_CHARGE, relatedChargeRequestId 연결)의 잔고를
     * 함께 담는다.
     */
    public static ChargeHistoryResponse fromRequest(ChargeRequest request, AccountTransaction approvedTransaction) {
        return new ChargeHistoryResponse(ChargeSource.ADMIN, request.getStatus(), request.getAmount(),
                approvedTransaction != null ? approvedTransaction.getBalanceAfter() : null,
                request.getReason(), request.getDecisionReason(),
                request.getRequestedAt(), request.getDecidedAt(), request.getRequestId());
    }
}
