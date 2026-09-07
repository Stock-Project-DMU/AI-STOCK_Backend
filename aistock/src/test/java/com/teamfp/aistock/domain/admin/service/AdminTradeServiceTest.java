package com.teamfp.aistock.domain.admin.service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import com.teamfp.aistock.domain.account.entity.Account;
import com.teamfp.aistock.domain.admin.dto.response.AdminTradeResponse;
import com.teamfp.aistock.domain.order.entity.Order;
import com.teamfp.aistock.domain.order.entity.OrderStatus;
import com.teamfp.aistock.domain.order.entity.OrderType;
import com.teamfp.aistock.domain.order.entity.PriceType;
import com.teamfp.aistock.domain.order.repository.OrderRepository;
import com.teamfp.aistock.domain.user.entity.Role;
import com.teamfp.aistock.domain.user.entity.User;
import com.teamfp.aistock.domain.user.entity.UserStatus;
import com.teamfp.aistock.global.exception.CustomException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
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

    @Mock
    private com.teamfp.aistock.domain.order.service.OrderService orderService;

    private AdminTradeService adminTradeService;

    private static final Long ORDER_ID = 100L;
    private static final Long ADMIN_ID = 1L;

    @BeforeEach
    void setUp() {
        adminTradeService = new AdminTradeService(orderRepository, orderService);
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
    @DisplayName("getTrades()는 조건 없이 호출하면 searchOrdersWithUser()에 전부 null로 넘긴다")
    void getTrades_noFilter_passesAllNull() {
        Pageable pageable = PageRequest.of(0, 20);
        Order order = orderOf();
        Page<Order> page = new PageImpl<>(List.of(order), pageable, 1);
        when(orderRepository.searchOrdersWithUser(null, null, null, null, null, null, null, pageable)).thenReturn(page);

        Page<AdminTradeResponse> result = adminTradeService.getTrades(null, null, null, null, null, null, null, pageable);

        assertThat(result.getContent()).hasSize(1);
        AdminTradeResponse response = result.getContent().get(0);
        assertThat(response.order().orderId()).isEqualTo(ORDER_ID);
        assertThat(response.userName()).isEqualTo("테스터");
        assertThat(response.loginId()).isEqualTo("tester");
    }

    @Test
    @DisplayName("getTrades()는 빈 문자열 query를 null로 정규화해서 searchOrdersWithUser()에 넘긴다")
    void getTrades_blankQuery_normalizedToNull() {
        Pageable pageable = PageRequest.of(0, 20);
        Page<Order> page = new PageImpl<>(List.of(), pageable, 0);
        when(orderRepository.searchOrdersWithUser(null, OrderStatus.EXECUTED, null, null, null, null, null, pageable))
                .thenReturn(page);

        adminTradeService.getTrades("   ", OrderStatus.EXECUTED, null, null, null, null, null, pageable);

        verify(orderRepository).searchOrdersWithUser(null, OrderStatus.EXECUTED, null, null, null, null, null, pageable);
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

    @Test
    @DisplayName("exportTradesCsv()는 UTF-8 BOM으로 시작하고 헤더·거래 정보를 CSV로 담는다")
    void exportTradesCsv_returnsCsvWithBomAndOrderRows() {
        Order order = orderOf();
        // orderedAt은 @CreatedDate(JPA Auditing)라 순수 빌더로는 채워지지 않으므로,
        // exportTradesCsv()가 getOrderedAt().toString()을 호출할 때 NPE가 나지 않도록 직접 채운다.
        ReflectionTestUtils.setField(order, "orderedAt", LocalDateTime.of(2026, 8, 1, 10, 0));
        Sort sort = Sort.by(Sort.Direction.DESC, "orderedAt");
        Pageable pageable = PageRequest.of(0, Integer.MAX_VALUE, sort);
        Page<Order> page = new PageImpl<>(List.of(order), pageable, 1);
        when(orderRepository.searchOrdersWithUser(null, null, null, null, null, null, null, pageable)).thenReturn(page);

        byte[] csv = adminTradeService.exportTradesCsv(null, null, null, null, null, null, null, sort);

        assertThat(csv[0]).isEqualTo((byte) 0xEF);
        assertThat(csv[1]).isEqualTo((byte) 0xBB);
        assertThat(csv[2]).isEqualTo((byte) 0xBF);
        String content = new String(csv, 3, csv.length - 3, StandardCharsets.UTF_8);
        assertThat(content).contains("주문번호,회원 아이디,계좌번호,종목코드,종목명,주문유형,가격유형,주문가,체결가,수량,상태,주문일시,체결일시");
        assertThat(content).contains("005930");
        assertThat(content).contains("삼성전자");
    }

    @Test
    @DisplayName("cancelTrade()는 OrderService.adminCancelOrder()에 위임한 뒤 최신 상태를 다시 조회해 반환한다")
    void cancelTrade_delegatesToOrderServiceThenReloads() {
        Order order = orderOf();
        order.cancel();
        com.teamfp.aistock.domain.admin.dto.request.AdminOrderCancelRequest request =
                new com.teamfp.aistock.domain.admin.dto.request.AdminOrderCancelRequest("비정상 주문으로 관리자 취소");
        when(orderRepository.findOrderWithUserById(ORDER_ID)).thenReturn(Optional.of(order));

        AdminTradeResponse result = adminTradeService.cancelTrade(ADMIN_ID, ORDER_ID, request);

        verify(orderService).adminCancelOrder(ADMIN_ID, ORDER_ID, "비정상 주문으로 관리자 취소");
        assertThat(result.order().status()).isEqualTo(com.teamfp.aistock.domain.order.entity.OrderStatus.CANCELLED);
    }
}
