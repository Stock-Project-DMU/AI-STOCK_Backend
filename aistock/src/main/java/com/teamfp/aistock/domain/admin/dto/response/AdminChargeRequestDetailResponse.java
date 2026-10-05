package com.teamfp.aistock.domain.admin.dto.response;

import java.time.LocalDateTime;
import java.util.List;

import com.teamfp.aistock.domain.account.entity.Account;
import com.teamfp.aistock.domain.account.entity.AccountStatus;
import com.teamfp.aistock.domain.account.entity.ChargeRequest;
import com.teamfp.aistock.domain.account.entity.ChargeRequestStatus;

/**
 * 관리자 충전 요청 상세 응답 DTO(feat/admin-improvements). 목록용 AdminChargeRequestResponse의 필드를 그대로 갖고
 * (기존 상세 화면 호환), 승인·거절을 판단하는 데 필요한 계좌 현황과 이 계좌의 최근 요청 이력을 더한다.
 *
 * - accountBalance/accountFrozenBalance/accountStatus: 요청한 계좌의 지금 잔고·지정가 주문에 묶인 금액·계좌 상태
 * - depositRemaining: 예치금 한도(일반 1조원)까지 남은 금액 — 승인 금액이 이보다 크면 승인 시 DEPOSIT_LIMIT_EXCEEDED
 * - chargeCount/maxChargeCount: 셀프 충전 사용 횟수(3회를 다 써야 충전 요청을 할 수 있다)
 * - recentRequests: 같은 계좌의 다른 충전 요청 최근 RECENT_REQUEST_LIMIT건(최신순, 이 요청 제외)
 */
public record AdminChargeRequestDetailResponse(
        Long requestId,
        Long accountId,
        String accountNumber,
        Long userId,
        String loginId,
        String userName,
        long amount,
        String reason,
        ChargeRequestStatus status,
        String decidedByName,
        String decisionReason,
        LocalDateTime requestedAt,
        LocalDateTime decidedAt,
        long accountBalance,
        long accountFrozenBalance,
        AccountStatus accountStatus,
        long depositRemaining,
        int chargeCount,
        int maxChargeCount,
        List<AdminChargeRequestResponse> recentRequests
) {

    public static final int RECENT_REQUEST_LIMIT = 10;

    public static AdminChargeRequestDetailResponse of(ChargeRequest chargeRequest, List<AdminChargeRequestResponse> recentRequests) {
        AdminChargeRequestResponse base = AdminChargeRequestResponse.from(chargeRequest);
        Account account = chargeRequest.getAccount();
        return new AdminChargeRequestDetailResponse(
                base.requestId(),
                base.accountId(),
                base.accountNumber(),
                base.userId(),
                base.loginId(),
                base.userName(),
                base.amount(),
                base.reason(),
                base.status(),
                base.decidedByName(),
                base.decisionReason(),
                base.requestedAt(),
                base.decidedAt(),
                account.getBalance(),
                account.getFrozenBalance(),
                account.getStatus(),
                account.getRemainingDepositAmount(),
                account.getChargeCount(),
                Account.MAX_CHARGE_COUNT,
                recentRequests
        );
    }
}
