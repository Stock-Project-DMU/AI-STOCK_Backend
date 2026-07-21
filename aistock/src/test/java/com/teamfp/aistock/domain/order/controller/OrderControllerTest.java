package com.teamfp.aistock.domain.order.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.teamfp.aistock.domain.order.dto.request.CreateOrderRequest;
import com.teamfp.aistock.domain.order.dto.response.CreateOrderResponse;
import com.teamfp.aistock.domain.order.entity.OrderStatus;
import com.teamfp.aistock.domain.order.entity.OrderType;
import com.teamfp.aistock.domain.order.entity.PriceType;
import com.teamfp.aistock.domain.order.service.OrderService;
import com.teamfp.aistock.domain.user.entity.Role;
import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;
import com.teamfp.aistock.global.security.CustomUserDetails;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * feature/order-market — OrderController.createOrder() 단위 테스트.
 *
 * priceType=MARKET/LIMIT 분기 책임이 OrderService.createMarketOrder()가 아니라 이 컨트롤러에
 * 있으므로(order-limit 병합 전까지는 LIMIT을 여기서 막는다), 그 분기 로직만 OrderService를
 * Mockito로 모킹해서 독립적으로 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class OrderControllerTest {

    @Mock
    private OrderService orderService;

    @InjectMocks
    private OrderController orderController;

    private static final Long USER_ID = 1L;
    private static final String STOCK_CODE = "005930";

    @BeforeEach
    void setUpAuthentication() {
        CustomUserDetails userDetails = new CustomUserDetails(USER_ID, Role.USER);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities()));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("priceType이 MARKET이면 OrderService.createMarketOrder()를 호출해 정상 체결한다")
    void createOrder_success_market() {
        CreateOrderRequest request = new CreateOrderRequest(STOCK_CODE, OrderType.BUY, 10, PriceType.MARKET, 0L);
        CreateOrderResponse response = new CreateOrderResponse(1L, STOCK_CODE, 70_000L, 10, OrderStatus.EXECUTED);
        when(orderService.createMarketOrder(USER_ID, request)).thenReturn(response);

        var result = orderController.createOrder(request);

        assertThat(result.getBody().getData().execPrice()).isEqualTo(70_000L);
        verify(orderService).createMarketOrder(USER_ID, request);
    }

    @Test
    @DisplayName("priceType이 LIMIT이면 OrderService를 호출하지 않고 INVALID_PRICE_TYPE 예외를 던진다")
    void createOrder_fail_limitNotSupportedYet() {
        // feature/order-limit이 아직 병합되지 않아 지정가 주문을 처리할 수단이 없다.
        // 검증 없이 진행하면 LIMIT 요청도 그대로 시장가로 체결돼버리는 게 이번에 고친 버그였다.
        CreateOrderRequest limitRequest = new CreateOrderRequest(STOCK_CODE, OrderType.BUY, 1, PriceType.LIMIT, 70_000L);

        assertThatThrownBy(() -> orderController.createOrder(limitRequest))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_PRICE_TYPE);

        verify(orderService, never()).createMarketOrder(anyLong(), any());
    }
}
