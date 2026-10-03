package com.teamfp.aistock.domain.order.service;
import com.teamfp.aistock.domain.order.dto.response.OrderHistoryResponse;
import com.teamfp.aistock.domain.order.entity.*;
import com.teamfp.aistock.global.exception.CustomException;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RealizedReturnServiceTest {
    private OrderHistoryResponse order(long id, OrderType type, long price, int quantity) {
        var time = LocalDateTime.of(2026, 9, 1, 9, 0).plusSeconds(id);
        return new OrderHistoryResponse(1L, id, "005930", "종목", type, PriceType.LIMIT,
                price, price, quantity, 0L, OrderStatus.EXECUTED, time, time);
    }
    @Test void calculatesWeightedCostAndPartialSalesInExecutionOrder() {
        var rows = RealizedReturnService.calculateReturns(List.of(
                order(5, OrderType.SELL, 180, 10),
                order(2, OrderType.BUY, 200, 10),
                order(1, OrderType.BUY, 100, 10),
                order(4, OrderType.BUY, 100, 10),
                order(3, OrderType.SELL, 170, 10)));
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).averageCost()).isEqualTo(125);
        assertThat(rows.get(0).profitAmount()).isEqualTo(550);
        assertThat(rows.get(1).profitAmount()).isEqualTo(200);
    }
    @Test void subtractsSellFeeFromRealizedProfit() {
        var time = LocalDateTime.of(2026, 9, 1, 9, 0);
        var buy = new OrderHistoryResponse(1L, 1L, "005930", "종목", OrderType.BUY, PriceType.MARKET,
                10_000L, 10_000L, 10, 0L, OrderStatus.EXECUTED, time, time);
        // 같은 가격에 전량 매도 — 매도 수수료 100원(0.1%)만큼 손실이어야 계좌 잔고 변화와 일치한다.
        var sell = new OrderHistoryResponse(1L, 2L, "005930", "종목", OrderType.SELL, PriceType.MARKET,
                10_000L, 10_000L, 10, 100L, OrderStatus.EXECUTED, time.plusMinutes(1), time.plusMinutes(1));

        var rows = RealizedReturnService.calculateReturns(List.of(buy, sell));

        assertThat(rows.get(0).profitAmount()).isEqualTo(-100L);
        assertThat(rows.get(0).profitRate()).isEqualTo(-0.1);
    }
    @Test void rejectsIncompleteHistoryInsteadOfInventingProfit() {
        assertThatThrownBy(() -> RealizedReturnService.calculateReturns(List.of(order(1, OrderType.SELL, 100, 1))))
                .isInstanceOf(CustomException.class);
    }
    @Test void delegatesAccountOwnershipCheckToOrderService() {
        var orders = mock(OrderService.class);
        when(orders.getMyOrderHistory(2L, 1L)).thenThrow(new CustomException(com.teamfp.aistock.global.exception.ErrorCode.ACCESS_DENIED));
        assertThatThrownBy(() -> new RealizedReturnService(orders).getReturns(2L, 1L)).isInstanceOf(CustomException.class);
    }
}
