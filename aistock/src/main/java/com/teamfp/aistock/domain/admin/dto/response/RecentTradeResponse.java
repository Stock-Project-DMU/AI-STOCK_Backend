package com.teamfp.aistock.domain.admin.dto.response;

import java.time.LocalDateTime;

import com.teamfp.aistock.domain.order.entity.Order;
import com.teamfp.aistock.domain.order.entity.OrderType;

/**
 * 관리자 대시보드 — 최근 체결 거래 한 건. AdminDashboardResponse.recentTrades 전용 내부 DTO다
 * (NAMING.md 8-14 참고). AdminTradeResponse와 달리 대시보드 요약 화면에 필요한 최소 필드만
 * 담는다 — priceType/orderPrice/status 등은 여기서 보여줄 필요가 없다(전체 거래 상세는
 * AdminTradeController가 담당).
 */
public record RecentTradeResponse(
        Long orderId,
        String userName,
        String stockCode,
        String stockName,
        OrderType orderType,
        Long execPrice,
        int quantity,
        LocalDateTime executedAt
) {

    public static RecentTradeResponse from(Order order) {
        return new RecentTradeResponse(
                order.getOrderId(),
                order.getAccount().getUser().getName(),
                order.getStockCode(),
                order.getStockName(),
                order.getOrderType(),
                order.getExecPrice(),
                order.getQuantity(),
                order.getExecutedAt()
        );
    }
}
