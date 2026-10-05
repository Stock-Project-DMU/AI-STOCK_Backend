package com.teamfp.aistock.domain.admin.dto.response;

import com.teamfp.aistock.domain.order.dto.response.OrderHistoryResponse;
import com.teamfp.aistock.domain.order.entity.Order;

/**
 * 관리자 — 전체 거래 목록 응답 DTO(feat/admin-improvements 이후 상세는 AdminTradeDetailResponse). 원래는 목록/상세를 별도 DTO로 나누지 않았다 —
 * AdminUserListResponse/AdminUserDetailResponse와 달리 상세라고 해서 추가로 내려줄 연관 정보
 * (계좌·보유종목 등)가 없고, 주문 한 건이 갖는 필드 자체가 목록·상세에서 동일하기 때문이다
 * (NAMING.md 8-15 참고).
 *
 * 주문 자체의 필드(stockCode/orderType/execPrice 등)는 mypage-account의
 * OrderHistoryResponse(AdminUserDetailResponse.orders가 이미 재사용 중)와 완전히 겹치므로
 * 거의 같은 필드의 DTO를 새로 만드는 대신 그대로 내부 필드로 재사용하고, 관리자 화면에만
 * 필요한 userName/loginId만 이 레코드에 추가한다(AdminUserDetailResponse가 AccountInfoResponse/
 * HoldingResponse/OrderHistoryResponse를 재사용하는 것과 동일한 패턴, 코드리뷰 반영).
 */
public record AdminTradeResponse(
        // feat/admin-improvements: 회원 상세로 바로 이동할 수 있게 userId, 어느 계좌의 주문인지 accountNumber,
        // 체결 금액(체결가 × 수량, 미체결이면 null)을 추가했다 — 전부 이미 fetch join된 값이라 추가 조회가 없다.
        Long userId,
        String userName,
        String loginId,
        String accountNumber,
        Long executedAmount,
        OrderHistoryResponse order
) {

    public static AdminTradeResponse from(Order order) {
        return new AdminTradeResponse(
                order.getAccount().getUser().getUserId(),
                order.getAccount().getUser().getName(),
                order.getAccount().getUser().getLoginId(),
                order.getAccount().getAccountNumber(),
                executedAmountOf(order),
                OrderHistoryResponse.from(order)
        );
    }

    static Long executedAmountOf(Order order) {
        return order.getExecPrice() == null ? null : order.getExecPrice() * order.getQuantity();
    }
}
