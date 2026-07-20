package com.teamfp.aistock.domain.order.dto.response;

import com.teamfp.aistock.domain.order.entity.Order;
import com.teamfp.aistock.domain.order.entity.OrderStatus;

/**
 * 주문 생성 결과 응답 DTO. 현재가 주문은 즉시 체결되므로 응답 시점에 execPrice/status가
 * 이미 확정(EXECUTED)된 값으로 채워진다.
 */
public record CreateOrderResponse(
        Long orderId,
        String stockCode,
        Long execPrice,
        int quantity,
        OrderStatus status
) {

    public static CreateOrderResponse from(Order order) {
        return new CreateOrderResponse(
                order.getOrderId(),
                order.getStockCode(),
                order.getExecPrice(),
                order.getQuantity(),
                order.getStatus()
        );
    }
}
