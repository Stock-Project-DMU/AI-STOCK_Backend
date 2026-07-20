package com.teamfp.aistock.domain.order.dto.request;

import com.teamfp.aistock.domain.order.entity.OrderType;
import com.teamfp.aistock.domain.order.entity.PriceType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 주문 생성 요청 DTO. 현재가(MARKET) 주문과 지정가(LIMIT) 주문이 POST /api/orders를
 * 공용으로 쓰기 때문에(NAMING.md 8-5/8-6) 같은 DTO를 함께 쓴다.
 *
 * orderPrice는 지정가 주문에서만 의미가 있다. 현재가 주문에서는 클라이언트가 값을
 * 보내더라도 서버가 실시간으로 조회한 현재가로 항상 덮어써서 체결한다 — 클라이언트가
 * 체결가를 임의로 조작하는 것을 막기 위함이다.
 */
public record CreateOrderRequest(

        @NotBlank(message = "종목 코드는 필수입니다.")
        String stockCode,

        @NotNull(message = "주문 유형(BUY/SELL)은 필수입니다.")
        OrderType orderType,

        @Positive(message = "주문 수량은 1주 이상이어야 합니다.")
        int quantity,

        @NotNull(message = "가격 유형(MARKET/LIMIT)은 필수입니다.")
        PriceType priceType,

        long orderPrice
) {
}
