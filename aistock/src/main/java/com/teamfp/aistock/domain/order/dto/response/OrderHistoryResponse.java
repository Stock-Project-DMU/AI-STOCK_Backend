package com.teamfp.aistock.domain.order.dto.response;

import java.time.LocalDateTime;

import com.teamfp.aistock.domain.order.entity.Order;
import com.teamfp.aistock.domain.order.entity.OrderStatus;
import com.teamfp.aistock.domain.order.entity.OrderType;
import com.teamfp.aistock.domain.order.entity.PriceType;

/**
 * 주문 내역(과거/현재 주문 한 건) 조회용 응답 DTO. CreateOrderResponse와 달리 체결 여부와
 * 무관하게 PENDING/EXECUTED/CANCELLED 상태를 모두 표현할 수 있는 필드를 갖는다.
 *
 * feature/mypage-profit(GET /api/orders)와 feature/admin-*(AdminUserDetailResponse 내부 필드)에서
 * 재사용하므로 order-limit 단계에서는 Order 엔티티 → 이 DTO로 변환하는 from()만 준비해둔다.
 */
public record OrderHistoryResponse(
        Long orderId,
        String stockCode,
        String stockName,
        OrderType orderType,
        PriceType priceType,
        long orderPrice,
        Long execPrice,
        int quantity,
        OrderStatus status,
        LocalDateTime orderedAt,
        LocalDateTime executedAt
) {

    public static OrderHistoryResponse from(Order order) {
        return new OrderHistoryResponse(
                order.getOrderId(),
                order.getStockCode(),
                order.getStockName(),
                order.getOrderType(),
                order.getPriceType(),
                order.getOrderPrice(),
                order.getExecPrice(),
                order.getQuantity(),
                order.getStatus(),
                order.getOrderedAt(),
                order.getExecutedAt()
        );
    }
}
