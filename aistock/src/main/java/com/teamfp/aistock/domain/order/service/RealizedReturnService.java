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
    // 관리자 계좌·회원 상세의 실현 손익 합계(feat/admin-improvements) — 계좌별로 calculateReturns()를 돌려 더한다.
    // 체결 기록이 맞지 않아 계산할 수 없으면 calculateReturns()와 같이 CustomException을 던진다(호출 쪽에서 처리).
    public static long sumRealizedProfit(List<OrderHistoryResponse> orders) {
        return orders.stream()
                .collect(java.util.stream.Collectors.groupingBy(OrderHistoryResponse::accountId))
                .values().stream()
                .flatMap(accountOrders -> calculateReturns(accountOrders).stream())
                .mapToLong(RealizedReturnResponse::profitAmount)
                .sum();
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
                    // 매도 거래 수수료(Order.fee)를 빼야 계좌 잔고에 실제로 반영된 손익과 같아진다(코드리뷰 반영).
                    long cost = position[1] * order.quantity();
                    long profit = (order.execPrice() - position[1]) * order.quantity() - order.fee();
                    returns.add(new RealizedReturnResponse(order.orderId(), order.stockCode(), order.stockName(), order.quantity(),
                            position[1], order.execPrice(), profit, cost == 0 ? 0 : profit * 100.0 / cost, order.executedAt()));
                    position[0] -= order.quantity();
                    if (position[0] == 0) position[1] = 0;
                }
            });
        Collections.reverse(returns);
        return returns;
    }
}
