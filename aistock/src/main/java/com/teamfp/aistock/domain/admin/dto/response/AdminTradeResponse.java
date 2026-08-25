package com.teamfp.aistock.domain.admin.dto.response;

import com.teamfp.aistock.domain.order.dto.response.OrderHistoryResponse;
import com.teamfp.aistock.domain.order.entity.Order;

/**
 * 관리자 — 전체 거래 목록·상세 조회 공용 응답 DTO. 목록/상세를 별도 DTO로 나누지 않는다 —
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
        String userName,
        String loginId,
        OrderHistoryResponse order
) {

    public static AdminTradeResponse from(Order order) {
        return new AdminTradeResponse(
                order.getAccount().getUser().getName(),
                order.getAccount().getUser().getLoginId(),
                OrderHistoryResponse.from(order)
        );
    }
}
