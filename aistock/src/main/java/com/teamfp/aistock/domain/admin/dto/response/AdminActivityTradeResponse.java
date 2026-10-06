package com.teamfp.aistock.domain.admin.dto.response;

import java.time.LocalDateTime;

import com.teamfp.aistock.domain.order.entity.OrderStatus;
import com.teamfp.aistock.domain.order.entity.OrderType;
import com.teamfp.aistock.domain.order.entity.PriceType;

/**
 * 전체 활동 기록 — 거래이력 한 줄의 상세(feat/admin-improvements). 주문 하나의 주문·체결·취소가 한 줄에 담긴다.
 * executedAmount는 체결 금액(체결가 × 수량, 미체결이면 null), cancel*은 관리자가 강제 취소한 주문일 때만 채워진다.
 */
public record AdminActivityTradeResponse(
        Long orderId,
        String accountNumber,
        String stockCode,
        String stockName,
        OrderType orderType,
        PriceType priceType,
        int quantity,
        long orderPrice,
        Long execPrice,
        Long executedAmount,
        OrderStatus status,
        LocalDateTime executedAt,
        String cancelReason,
        String cancelledByLoginId,
        LocalDateTime cancelledAt
) {
}
