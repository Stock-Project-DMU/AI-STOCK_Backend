package com.teamfp.aistock.domain.order.dto.request;

import com.teamfp.aistock.domain.order.entity.OrderType;
import com.teamfp.aistock.domain.order.entity.PriceType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 주문 생성 요청 DTO. 현재가(MARKET) 주문과 지정가(LIMIT) 주문이 POST /api/orders를
 * 공용으로 쓰기 때문에(NAMING.md 8-5/8-6) 같은 DTO를 함께 쓴다.
 *
 * orderPrice는 지정가 주문에서만 의미가 있다. 현재가 주문에서는 클라이언트가 값을
 * 보내더라도 서버가 실시간으로 조회한 현재가로 항상 덮어써서 체결한다 — 클라이언트가
 * 체결가를 임의로 조작하는 것을 막기 위함이다.
 */
public record CreateOrderRequest(

        // feature/mypage-account부터 유저가 계좌를 최대 3개까지 가질 수 있어, 어느 계좌로
        // 주문할지 클라이언트가 명시해야 한다. OrderService는 이 accountId와 인증된 userId를
        // 함께 검증해(accountRepository.findByAccountIdAndUserId) 소유권도 같이 확인한다.
        @NotNull(message = "계좌 ID는 필수입니다.")
        Long accountId,

        @NotBlank(message = "종목 코드는 필수입니다.")
        String stockCode,

        @NotNull(message = "주문 유형(BUY/SELL)은 필수입니다.")
        OrderType orderType,

        @Positive(message = "주문 수량은 1주 이상이어야 합니다.")
        int quantity,

        @NotNull(message = "가격 유형(MARKET/LIMIT)은 필수입니다.")
        PriceType priceType,

        // MARKET 주문에서는 서버가 항상 실시간 현재가로 덮어써서 쓰지 않는 값이지만,
        // LIMIT 주문(feature/order-limit)에서는 실제 주문가로 쓰이므로 음수 같은 말이 안 되는
        // 값이 들어오는 걸 미리 막아둔다. MARKET 요청에서는 클라이언트가 0을 보내면 된다.
        @PositiveOrZero(message = "주문 가격은 0 이상이어야 합니다.")
        long orderPrice
) {
}
