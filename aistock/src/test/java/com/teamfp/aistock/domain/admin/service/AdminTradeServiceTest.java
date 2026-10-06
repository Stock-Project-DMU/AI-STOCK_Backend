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
import com.teamfp.aistock.domain.admin.dto.request.AdminSearchConditionDto;
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
import static org.mockito.ArgumentMatchers.any;
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

    @Mock
    private com.teamfp.aistock.domain.admin.repository.AuditLogRepository auditLogRepository;

    @Mock
    private com.teamfp.aistock.domain.account.repository.AccountTransactionRepository accountTransactionRepository;

    private AdminTradeService adminTradeService;

    private static final Long ORDER_ID = 100L;
    private static final Long ADMIN_ID = 1L;

    @BeforeEach
    void setUp() {
        adminTradeService = new AdminTradeService(orderRepository, orderService, auditLogRepository, accountTransactionRepository);
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
        when(orderRepository.searchOrdersWithUser(null, null, null, "ALL", false, null, null, null, null, null, null, pageable)).thenReturn(page);

        Page<AdminTradeResponse> result = adminTradeService.getTrades(AdminSearchConditionDto.of(null, null, null), null, null, null, null, null, null, pageable);

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
        when(orderRepository.searchOrdersWithUser(null, null, null, "ALL", false, OrderStatus.EXECUTED, null, null, null, null, null, pageable))
                .thenReturn(page);

        adminTradeService.getTrades(AdminSearchConditionDto.of("   ", null, null), OrderStatus.EXECUTED, null, null, null, null, null, pageable);

        verify(orderRepository).searchOrdersWithUser(null, null, null, "ALL", false, OrderStatus.EXECUTED, null, null, null, null, null, pageable);
    }

    @Test
    @DisplayName("getTradeDetail()은 존재하는 orderId면 상세 정보를 반환한다")
    void getTradeDetail_found() {
        Order order = orderOf();
        when(orderRepository.findOrderWithUserById(ORDER_ID)).thenReturn(Optional.of(order));

        com.teamfp.aistock.domain.admin.dto.response.AdminTradeDetailResponse result = adminTradeService.getTradeDetail(ORDER_ID);

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
        when(orderRepository.searchOrdersWithUser(null, null, null, "ALL", false, null, null, null, null, null, null, pageable)).thenReturn(page);

        byte[] csv = adminTradeService.exportTradesCsv(AdminSearchConditionDto.of(null, null, null), null, null, null, null, null, null, sort);

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
        com.teamfp.aistock.domain.admin.entity.AuditLog cancelLog = com.teamfp.aistock.domain.admin.entity.AuditLog.builder()
                .adminUserId(ADMIN_ID).adminLoginId("admin").action(AuditLogService.ACTION_ORDER_CANCEL)
                .targetType(AuditLogService.TARGET_ORDER).targetId(ORDER_ID).reason("비정상 주문으로 관리자 취소").build();
        ReflectionTestUtils.setField(cancelLog, "createdAt", LocalDateTime.of(2026, 10, 4, 10, 0));
        when(auditLogRepository.findFirstByActionAndTargetTypeAndTargetIdOrderByCreatedAtDesc(
                AuditLogService.ACTION_ORDER_CANCEL, AuditLogService.TARGET_ORDER, ORDER_ID)).thenReturn(Optional.of(cancelLog));

        com.teamfp.aistock.domain.admin.dto.response.AdminTradeDetailResponse result =
                adminTradeService.cancelTrade(ADMIN_ID, ORDER_ID, request);

        verify(orderService).adminCancelOrder(ADMIN_ID, ORDER_ID, "비정상 주문으로 관리자 취소");
        assertThat(result.order().status()).isEqualTo(com.teamfp.aistock.domain.order.entity.OrderStatus.CANCELLED);
        // 취소된 주문 상세에는 감사 로그의 취소 사유·처리 관리자·시각이 담긴다(feat/admin-improvements).
        assertThat(result.cancelReason()).isEqualTo("비정상 주문으로 관리자 취소");
        assertThat(result.cancelledByLoginId()).isEqualTo("admin");
        assertThat(result.cancelledAt()).isEqualTo(LocalDateTime.of(2026, 10, 4, 10, 0));
    }

    @Test
    @DisplayName("거래 상세·목록에는 회원번호·계좌번호·체결 금액(체결가×수량)이 담기고, 미체결 주문은 감사 로그를 찾지 않는다")
    void tradeDetail_containsAccountAndExecutedAmount() {
        Order order = orderOf();
        when(orderRepository.findOrderWithUserById(ORDER_ID)).thenReturn(Optional.of(order));

        com.teamfp.aistock.domain.admin.dto.response.AdminTradeDetailResponse pending = adminTradeService.getTradeDetail(ORDER_ID);
        assertThat(pending.userId()).isEqualTo(1L);
        assertThat(pending.accountNumber()).isEqualTo("ACC-1");
        assertThat(pending.executedAmount()).isNull();
        assertThat(pending.cancelReason()).isNull();
        verify(auditLogRepository, org.mockito.Mockito.never())
                .findFirstByActionAndTargetTypeAndTargetIdOrderByCreatedAtDesc(any(), any(), any());

        order.execute(71_000L);
        assertThat(AdminTradeResponse.from(order).executedAmount()).isEqualTo(71_000L);
    }
    @Test
    @DisplayName("체결된 매도 주문 상세에는 마이페이지와 같은 계산의 실현 손익과, 이 주문으로 생긴 잔고 내역이 담긴다")
    void tradeDetail_sellHasRealizedProfitAndBalanceChanges() {
        Order buy = orderOf();
        ReflectionTestUtils.setField(buy, "orderId", ORDER_ID - 1);
        buy.execute(100_000L);
        ReflectionTestUtils.setField(buy, "executedAt", LocalDateTime.of(2026, 10, 1, 9, 0));
        Order sell = Order.builder()
                .account(buy.getAccount())
                .stockCode("005930")
                .stockName("삼성전자")
                .orderType(OrderType.SELL)
                .priceType(PriceType.MARKET)
                .orderPrice(120_000L)
                .quantity(1)
                .build();
        ReflectionTestUtils.setField(sell, "orderId", ORDER_ID);
        sell.execute(120_000L);
        when(orderRepository.findOrderWithUserById(ORDER_ID)).thenReturn(Optional.of(sell));
        when(orderRepository.findExecutedByAccountIdAndStockCode(10L, "005930")).thenReturn(List.of(sell, buy));
        com.teamfp.aistock.domain.account.entity.AccountTransaction sellTx =
                com.teamfp.aistock.domain.account.entity.AccountTransaction.builder()
                        .account(sell.getAccount())
                        .type(com.teamfp.aistock.domain.account.entity.AccountTransactionType.ORDER_SELL)
                        .amount(120_000L).balanceBefore(1_000_000L).balanceAfter(1_120_000L)
                        .relatedOrderId(ORDER_ID).build();
        when(accountTransactionRepository.findAllByRelatedOrderIdOrderByCreatedAtAscTransactionIdAsc(ORDER_ID))
                .thenReturn(List.of(sellTx));

        com.teamfp.aistock.domain.admin.dto.response.AdminTradeDetailResponse result = adminTradeService.getTradeDetail(ORDER_ID);

        // (120,000 - 평단 100,000) × 1주 - 매도 수수료
        assertThat(result.realizedProfit()).isNotNull();
        assertThat(result.realizedProfit().averageCost()).isEqualTo(100_000L);
        assertThat(result.realizedProfit().profitAmount()).isEqualTo(20_000L - sell.getFee());
        assertThat(result.balanceChanges()).singleElement().satisfies(change -> {
            assertThat(change.balanceBefore()).isEqualTo(1_000_000L);
            assertThat(change.balanceAfter()).isEqualTo(1_120_000L);
        });
    }

    @Test
    @DisplayName("이전 매수 기록이 맞지 않아 실현 손익을 계산할 수 없어도 상세 조회는 실패하지 않고 realizedProfit만 null이다")
    void tradeDetail_inconsistentHistory_realizedProfitNull() {
        Order sell = orderOf();
        ReflectionTestUtils.setField(sell, "orderType", OrderType.SELL);
        sell.execute(120_000L);
        when(orderRepository.findOrderWithUserById(ORDER_ID)).thenReturn(Optional.of(sell));
        when(orderRepository.findExecutedByAccountIdAndStockCode(10L, "005930")).thenReturn(List.of(sell));

        assertThat(adminTradeService.getTradeDetail(ORDER_ID).realizedProfit()).isNull();
    }
}
