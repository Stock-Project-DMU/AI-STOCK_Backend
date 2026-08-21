package com.teamfp.aistock.domain.admin.dto.response;

import java.time.LocalDateTime;

import com.teamfp.aistock.domain.order.entity.Order;
import com.teamfp.aistock.domain.order.entity.OrderStatus;
import com.teamfp.aistock.domain.order.entity.OrderType;
import com.teamfp.aistock.domain.order.entity.PriceType;

/**
 * 관리자 — 전체 거래 목록·상세 조회 공용 응답 DTO. 목록/상세를 별도 DTO로 나누지 않는다 —
 * AdminUserListResponse/AdminUserDetailResponse와 달리 상세라고 해서 추가로 내려줄 연관 정보
 * (계좌·보유종목 등)가 없고, 주문 한 건이 갖는 필드 자체가 목록·상세에서 동일하기 때문이다
 * (NAMING.md 8-15 참고).
 */
public record AdminTradeResponse(
        Long orderId,
        String userName,
        String loginId,
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

    public static AdminTradeResponse from(Order order) {
        return new AdminTradeResponse(
                order.getOrderId(),
                order.getAccount().getUser().getName(),
                order.getAccount().getUser().getLoginId(),
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
