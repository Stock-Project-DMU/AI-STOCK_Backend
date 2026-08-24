package com.teamfp.aistock.domain.admin.service;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.teamfp.aistock.domain.account.entity.Account;
import com.teamfp.aistock.domain.admin.dto.response.AdminDashboardResponse;
import com.teamfp.aistock.domain.order.entity.Order;
import com.teamfp.aistock.domain.order.entity.OrderStatus;
import com.teamfp.aistock.domain.order.entity.OrderType;
import com.teamfp.aistock.domain.order.entity.PriceType;
import com.teamfp.aistock.domain.order.repository.OrderRepository;
import com.teamfp.aistock.domain.user.entity.Role;
import com.teamfp.aistock.domain.user.entity.User;
import com.teamfp.aistock.domain.user.entity.UserStatus;
import com.teamfp.aistock.domain.user.repository.UserRepository;
import com.teamfp.aistock.global.redis.RedisOnlineStatusService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * AdminDashboardService — 총 사용자/온라인/거래량/최근거래 요약이 UserRepository·OrderRepository의
 * 3주차 집계 메서드와 RedisOnlineStatusService.countOnline()을 그대로 조합해 반환하는지 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class AdminDashboardServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private RedisOnlineStatusService redisOnlineStatusService;

    private AdminDashboardService adminDashboardService;

    @BeforeEach
    void setUp() {
        adminDashboardService = new AdminDashboardService(userRepository, orderRepository, redisOnlineStatusService);
    }

    private Order executedOrderOf(long execPrice, int quantity) {
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
                .accountName("계좌A")
                .accountNumber("ACC-1")
                .openedAt(LocalDate.now())
                .baseBalance(1_000_000L)
                .balance(1_000_000L)
                .build();

        Order order = Order.builder()
                .account(account)
                .stockCode("005930")
                .stockName("삼성전자")
                .orderType(OrderType.BUY)
                .priceType(PriceType.MARKET)
                .orderPrice(execPrice)
                .quantity(quantity)
                .build();
        order.execute(execPrice);
        ReflectionTestUtils.setField(order, "orderId", 1L);
        return order;
    }

    @Test
    @DisplayName("getDashboard()는 UserRepository/OrderRepository 집계 메서드와 온라인 수를 그대로 조합해 반환한다")
    void getDashboard_combinesAggregatesAsIs() {
        when(userRepository.countByIsActiveTrue()).thenReturn(42L);
        when(redisOnlineStatusService.countOnline()).thenReturn(7L);
        when(orderRepository.countByStatus(OrderStatus.EXECUTED)).thenReturn(123L);
        when(orderRepository.sumExecutedAmount()).thenReturn(9_876_000L);
        Order recentOrder = executedOrderOf(70_000L, 3);
        when(orderRepository.findTop20ByStatusOrderByExecutedAtDesc(OrderStatus.EXECUTED))
                .thenReturn(List.of(recentOrder));

        AdminDashboardResponse result = adminDashboardService.getDashboard();

        assertThat(result.totalUserCount()).isEqualTo(42L);
        assertThat(result.onlineUserCount()).isEqualTo(7L);
        assertThat(result.totalTradeCount()).isEqualTo(123L);
        assertThat(result.totalTradeAmount()).isEqualTo(9_876_000L);
        assertThat(result.recentTrades()).hasSize(1);
        assertThat(result.recentTrades().get(0).orderId()).isEqualTo(recentOrder.getOrderId());
        assertThat(result.recentTrades().get(0).userName()).isEqualTo("테스터");
        assertThat(result.recentTrades().get(0).execPrice()).isEqualTo(70_000L);
        assertThat(result.recentTrades().get(0).quantity()).isEqualTo(3);
    }

    @Test
    @DisplayName("체결된 거래가 없으면 recentTrades는 빈 리스트를 반환한다")
    void getDashboard_noExecutedOrders_returnsEmptyRecentTrades() {
        when(userRepository.countByIsActiveTrue()).thenReturn(0L);
        when(redisOnlineStatusService.countOnline()).thenReturn(0L);
        when(orderRepository.countByStatus(OrderStatus.EXECUTED)).thenReturn(0L);
        when(orderRepository.sumExecutedAmount()).thenReturn(0L);
        when(orderRepository.findTop20ByStatusOrderByExecutedAtDesc(OrderStatus.EXECUTED))
                .thenReturn(List.of());

        AdminDashboardResponse result = adminDashboardService.getDashboard();

        assertThat(result.recentTrades()).isEmpty();
    }
}
