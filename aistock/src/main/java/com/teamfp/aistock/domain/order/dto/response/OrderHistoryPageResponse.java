package com.teamfp.aistock.domain.order.dto.response;

import java.util.List;

import org.springframework.data.domain.Slice;

import com.teamfp.aistock.domain.order.entity.Order;

/**
 * 주문내역 나눠 받기 응답(GET /api/orders?accountId=&page=&size=, 2026-10-13). 주문 목록 영역 안에서 스크롤하면 다음 묶음을
 * 이어 붙이는 화면용이라 전체 개수는 세지 않고(Slice) 다음 묶음이 있는지(hasNext)만 알려준다.
 *
 * @param orders  최신 주문순(주문 시각 → 주문번호 내림차순) 묶음
 * @param page    이번 묶음 번호(0부터)
 * @param size    묶음 크기
 * @param hasNext 다음 묶음이 있으면 true — false면 화면은 더 부르지 않는다
 */
public record OrderHistoryPageResponse(
        List<OrderHistoryResponse> orders,
        int page,
        int size,
        boolean hasNext
) {
    public static OrderHistoryPageResponse from(Slice<Order> slice) {
        return new OrderHistoryPageResponse(slice.getContent().stream().map(OrderHistoryResponse::from).toList(),
                slice.getNumber(), slice.getSize(), slice.hasNext());
    }
}
