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
import com.teamfp.aistock.global.security.CustomUserDetails;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * feature/order-market, feature/order-limit — OrderController.createOrder()/cancelOrder() 단위 테스트.
 *
 * priceType=MARKET/LIMIT 분기 책임이 OrderService가 아니라 이 컨트롤러에 있으므로,
 * 그 분기 로직만 OrderService를 Mockito로 모킹해서 독립적으로 검증한다.
 *
 * order-limit 반영: priceType=LIMIT 요청을 막던 기존 테스트(createOrder_fail_limitNotSupportedYet)는
 * createLimitOrder()가 생기면서 더 이상 유효한 시나리오가 아니라, LIMIT 요청이 실제로
 * createLimitOrder()로 라우팅되는지 검증하는 테스트로 대체했다.
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
        verify(orderService, never()).createLimitOrder(USER_ID, request);
    }

    @Test
    @DisplayName("priceType이 LIMIT이면 OrderService.createLimitOrder()를 호출해 PENDING으로 등록한다")
    void createOrder_success_limit() {
        CreateOrderRequest request = new CreateOrderRequest(STOCK_CODE, OrderType.BUY, 1, PriceType.LIMIT, 70_000L);
        CreateOrderResponse response = new CreateOrderResponse(1L, STOCK_CODE, null, 1, OrderStatus.PENDING);
        when(orderService.createLimitOrder(USER_ID, request)).thenReturn(response);

        var result = orderController.createOrder(request);

        assertThat(result.getBody().getData().status()).isEqualTo(OrderStatus.PENDING);
        verify(orderService).createLimitOrder(USER_ID, request);
        verify(orderService, never()).createMarketOrder(USER_ID, request);
    }

    @Test
    @DisplayName("주문 취소 요청은 OrderService.cancelOrder()를 호출한다")
    void cancelOrder_success() {
        Long orderId = 10L;

        var result = orderController.cancelOrder(orderId);

        assertThat(result.getBody().isSuccess()).isTrue();
        verify(orderService).cancelOrder(USER_ID, orderId);
    }
}
