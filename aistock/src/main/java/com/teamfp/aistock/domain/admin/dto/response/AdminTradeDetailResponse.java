package com.teamfp.aistock.domain.admin.dto.response;

import java.time.LocalDateTime;
import java.util.List;

import com.teamfp.aistock.domain.account.dto.response.AccountTransactionResponse;
import com.teamfp.aistock.domain.admin.entity.AuditLog;
import com.teamfp.aistock.domain.order.dto.response.OrderHistoryResponse;
import com.teamfp.aistock.domain.order.dto.response.RealizedReturnResponse;
import com.teamfp.aistock.domain.order.entity.Order;

/**
 * 관리자 거래 상세 응답 DTO(feat/admin-improvements). 목록용 AdminTradeResponse의 필드를 그대로 갖고(기존
 * 상세 화면이 쓰던 userName/loginId/order 그대로 호환), 상세에서만 필요한 관리자 취소 정보를 더한다.
 *
 * - cancelReason/cancelledByLoginId/cancelledAt: 관리자가 강제 취소한 주문이면 감사 로그(ORDER_CANCEL)의
 *   사유·처리 관리자·시각. 사용자가 직접 취소했거나 취소되지 않은 주문이면 null이다.
 * - realizedProfit: 체결된 매도 주문의 실현 손익(평단가·매도가·손익·수익률) — 마이페이지 실현 손익과 같은 계산
 *   (RealizedReturnService.calculateReturns). 매수·미체결·취소 주문이거나 이전 매수 기록이 맞지 않으면 null이다.
 * - balanceChanges: 이 주문으로 생긴 잔고 내역(매수 차감·매도 입금·수수료·환불)을 기록 순서대로 — 잔고가 얼마에서
 *   얼마로 바뀌었는지(balanceBefore → balanceAfter)
 */
public record AdminTradeDetailResponse(
        Long userId,
        String userName,
        String loginId,
        String accountNumber,
        Long executedAmount,
        OrderHistoryResponse order,
        String cancelReason,
        String cancelledByLoginId,
        LocalDateTime cancelledAt,
        RealizedReturnResponse realizedProfit,
        List<AccountTransactionResponse> balanceChanges
) {

    public static AdminTradeDetailResponse of(Order order, AuditLog cancelLog, RealizedReturnResponse realizedProfit,
            List<AccountTransactionResponse> balanceChanges) {
        return new AdminTradeDetailResponse(
                order.getAccount().getUser().getUserId(),
                order.getAccount().getUser().getName(),
                order.getAccount().getUser().getLoginId(),
                order.getAccount().getAccountNumber(),
                AdminTradeResponse.executedAmountOf(order),
                OrderHistoryResponse.from(order),
                cancelLog == null ? null : cancelLog.getReason(),
                cancelLog == null ? null : cancelLog.getAdminLoginId(),
                cancelLog == null ? null : cancelLog.getCreatedAt(),
                realizedProfit,
                balanceChanges
        );
    }
}
