package com.teamfp.aistock.domain.admin.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import com.teamfp.aistock.domain.account.entity.Account;
import com.teamfp.aistock.domain.admin.dto.response.AdminTradeResponse;
import com.teamfp.aistock.domain.order.entity.Order;
import com.teamfp.aistock.domain.order.entity.OrderType;
import com.teamfp.aistock.domain.order.entity.PriceType;
import com.teamfp.aistock.domain.order.repository.OrderRepository;
import com.teamfp.aistock.domain.user.entity.Role;
import com.teamfp.aistock.domain.user.entity.User;
import com.teamfp.aistock.domain.user.entity.UserStatus;
import com.teamfp.aistock.global.exception.CustomException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * AdminTradeService — 관리자 전체 거래 목록·상세 조회. 목록/상세 모두 account, account.user까지
 * JOIN FETCH된 OrderRepository 전용 메서드를 그대로 위임하는지, 상세에서 못 찾으면
 * ORDER_NOT_FOUND를 던지는지를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class AdminTradeServiceTest {

    @Mock
    private OrderRepository orderRepository;

    private AdminTradeService adminTradeService;

    private static final Long ORDER_ID = 100L;

    @BeforeEach
    void setUp() {
        adminTradeService = new AdminTradeService(orderRepository);
    }

    private Order orderOf() {
        User user = User.builder()
                .loginId("tester")
                .name("테스터")
                .role(Role.USER)
                .status(UserStatus.ACTIVE)
                .isActive(true)
                .build();
        ReflectionTestUtils.setField(user, "userId", 1L);

        Account account = Account.builder()
                .user(user)
                .accountName("계좌1")
                .accountNumber("ACC-1")
                .openedAt(LocalDate.now())
                .baseBalance(1_000_000L)
                .balance(1_000_000L)
                .build();
        ReflectionTestUtils.setField(account, "accountId", 10L);

        Order order = Order.builder()
                .account(account)
                .stockCode("005930")
                .stockName("삼성전자")
                .orderType(OrderType.BUY)
                .priceType(PriceType.MARKET)
                .orderPrice(70_000L)
                .quantity(1)
                .build();
        ReflectionTestUtils.setField(order, "orderId", ORDER_ID);
        return order;
    }

    @Test
    @DisplayName("getTrades()는 account.user까지 JOIN FETCH된 findAllOrdersWithUser를 그대로 위임한다")
    void getTrades_delegatesToFindAllOrdersWithUser() {
        Pageable pageable = PageRequest.of(0, 20);
        Order order = orderOf();
        Page<Order> page = new PageImpl<>(List.of(order), pageable, 1);
        when(orderRepository.findAllOrdersWithUser(pageable)).thenReturn(page);

        Page<AdminTradeResponse> result = adminTradeService.getTrades(pageable);

        assertThat(result.getContent()).hasSize(1);
        AdminTradeResponse response = result.getContent().get(0);
        assertThat(response.order().orderId()).isEqualTo(ORDER_ID);
        assertThat(response.userName()).isEqualTo("테스터");
        assertThat(response.loginId()).isEqualTo("tester");
    }

    @Test
    @DisplayName("getTradeDetail()은 존재하는 orderId면 상세 정보를 반환한다")
    void getTradeDetail_found() {
        Order order = orderOf();
        when(orderRepository.findOrderWithUserById(ORDER_ID)).thenReturn(Optional.of(order));

        AdminTradeResponse result = adminTradeService.getTradeDetail(ORDER_ID);

        assertThat(result.order().orderId()).isEqualTo(ORDER_ID);
        assertThat(result.order().stockCode()).isEqualTo("005930");
    }

    @Test
    @DisplayName("getTradeDetail()은 존재하지 않는 orderId면 ORDER_NOT_FOUND 예외를 던진다")
    void getTradeDetail_notFound() {
        when(orderRepository.findOrderWithUserById(ORDER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adminTradeService.getTradeDetail(ORDER_ID))
                .isInstanceOf(CustomException.class);
    }
}
