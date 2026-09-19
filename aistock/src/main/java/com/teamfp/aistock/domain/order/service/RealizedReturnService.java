package com.teamfp.aistock.domain.order.service;
import com.teamfp.aistock.domain.order.dto.response.*;
import com.teamfp.aistock.domain.order.entity.*;
import com.teamfp.aistock.global.exception.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
@Service @RequiredArgsConstructor
public class RealizedReturnService {
    private final OrderService orderService;
    @Transactional(readOnly = true)
    public List<RealizedReturnResponse> getReturns(Long userId, Long accountId) {
        return calculateReturns(orderService.getMyOrderHistory(userId, accountId));
    }
    public static List<RealizedReturnResponse> calculateReturns(List<OrderHistoryResponse> orders) {
        Map<String, long[]> holdings = new HashMap<>();
        List<RealizedReturnResponse> returns = new ArrayList<>();
        orders.stream().filter(order -> order.status() == OrderStatus.EXECUTED && order.execPrice() != null && order.executedAt() != null)
            .sorted(Comparator.comparing(OrderHistoryResponse::executedAt).thenComparing(OrderHistoryResponse::orderId))
            .forEach(order -> {
                long[] position = holdings.computeIfAbsent(order.stockCode(), code -> new long[2]);
                if (order.orderType() == OrderType.BUY) {
                    position[1] = (position[0] * position[1] + order.quantity() * order.execPrice()) / (position[0] + order.quantity());
                    position[0] += order.quantity();
                } else {
                    if (position[0] < order.quantity()) throw new CustomException(ErrorCode.INVALID_INPUT);
                    long profit = (order.execPrice() - position[1]) * order.quantity();
                    returns.add(new RealizedReturnResponse(order.orderId(), order.stockCode(), order.stockName(), order.quantity(),
                            position[1], order.execPrice(), profit, position[1] == 0 ? 0 : (order.execPrice() - position[1]) * 100.0 / position[1], order.executedAt()));
                    position[0] -= order.quantity();
                    if (position[0] == 0) position[1] = 0;
                }
            });
        Collections.reverse(returns);
        return returns;
    }
}
