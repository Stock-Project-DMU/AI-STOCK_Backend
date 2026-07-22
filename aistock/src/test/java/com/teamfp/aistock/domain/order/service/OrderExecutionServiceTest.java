package com.teamfp.aistock.domain.order.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import com.teamfp.aistock.domain.account.entity.Account;
import com.teamfp.aistock.domain.order.dto.PendingOrderDto;
import com.teamfp.aistock.domain.order.entity.Holding;
import com.teamfp.aistock.domain.order.entity.Order;
import com.teamfp.aistock.domain.order.entity.OrderStatus;
import com.teamfp.aistock.domain.order.entity.OrderType;
import com.teamfp.aistock.domain.order.entity.PriceType;
import com.teamfp.aistock.domain.order.repository.HoldingRepository;
import com.teamfp.aistock.domain.order.repository.OrderRepository;
import com.teamfp.aistock.domain.user.entity.Role;
import com.teamfp.aistock.domain.user.entity.User;
import com.teamfp.aistock.global.redis.RedisPendingOrderService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * feature/order-limit — OrderExecutionService.execute()/checkAndExecute() 단위 테스트.
 *
 * LS증권 WebSocket tick 파이프라인은 아직 다른 브랜치가 구현 중이라, tick 수신을 흉내 내려면
 * checkAndExecute(stockCode, currentPrice)를 직접 호출한다. Repository/Redis는 Mockito로 모킹해서
 * 체결 조건 판정 + 잔고/보유종목 정산 + 동시 체결 충돌(낙관적 락) 시 재시도 로직만 독립적으로 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class OrderExecutionServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private HoldingRepository holdingRepository;

    @Mock
    private RedisPendingOrderService redisPendingOrderService;

    @InjectMocks
    private OrderExecutionService orderExecutionService;

    private static final String STOCK_CODE = "005930";

    private Account account;

    @BeforeEach
    void setUp() {
        User user = User.builder()
                .loginId("tester")
                .name("테스터")
                .role(Role.USER)
                .isActive(true)
                .build();

        account = Account.builder()
                .user(user)
                .accountName("테스트계좌")
                .accountNumber("ACC-0001")
                .openedAt(LocalDate.now())
                .baseBalance(1_000_000L)
                .balance(1_000_000L)
                .build();

        // 운영 코드에서는 Spring이 @Lazy 프록시를 self 필드에 주입해주지만, @InjectMocks로 만든
        // 이 테스트 인스턴스는 스프링 컨테이너 없이 생성되므로 self가 null로 남는다. 이 단위
        // 테스트는 checkAndExecute()가 self.execute(...)를 올바른 인자로 호출하는지만 검증하면
        // 되므로(실제 AOP 프록시 동작 자체는 여기서 검증 대상이 아니다), 자기 자신을 그대로 넣어준다.
        ReflectionTestUtils.setField(orderExecutionService, "self", orderExecutionService);

        // HoldingSettlementService도 @Mock이 아니라 실제 구현을 그대로 쓴다 — 이 테스트들이
        // 검증하는 "보유종목 수량/평단가가 실제로 어떻게 바뀌는지"는 그 서비스 내부 로직이라,
        // 모킹해버리면 여기서 검증할 대상이 사라진다. 대신 그 서비스가 의존하는
        // holdingRepository는 이미 모킹돼 있는 것을 그대로 재사용한다.
        ReflectionTestUtils.setField(orderExecutionService, "holdingSettlementService", new HoldingSettlementService(holdingRepository));
    }

    private Order pendingOrder(OrderType orderType, long orderPrice, int quantity) {
        return Order.builder()
                .account(account)
                .stockCode(STOCK_CODE)
                .stockName("삼성전자")
                .orderType(orderType)
                .priceType(PriceType.LIMIT)
                .orderPrice(orderPrice)
                .quantity(quantity)
                .build();
    }

    private PendingOrderDto pendingOrderDto(Long orderId, OrderType orderType, long limitPrice, int quantity) {
        return PendingOrderDto.builder()
                .orderId(orderId)
                .accountId(account.getAccountId())
                .orderType(orderType.name())
                .limitPrice(limitPrice)
                .quantity(quantity)
                .stockCode(STOCK_CODE)
                .stockName("삼성전자")
                .build();
    }

    @Nested
    @DisplayName("execute() - 매수 체결")
    class ExecuteBuy {

        @Test
        @DisplayName("매수 주문이 체결되면 frozenBalance가 해제되고 지정가보다 싸게 체결된 차액이 balance로 환급된다")
        void success_settlesFrozenBalanceAndCreatesHolding() {
            account.freezeForOrder(700_000L); // 지정가 70,000원 * 10주 만큼 미리 동결돼 있던 상태
            Order order = pendingOrder(OrderType.BUY, 70_000L, 10);
            when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(order));
            when(holdingRepository.findByAccountIdAndStockCode(any(), anyString())).thenReturn(Optional.empty());

            // 지정가 70,000원인데 현재가 65,000원에 체결 → 5,000원 * 10주 = 50,000원 환급
            orderExecutionService.execute(pendingOrderDto(1L, OrderType.BUY, 70_000L, 10), 65_000L);

            assertThat(account.getFrozenBalance()).isZero();
            assertThat(account.getBalance()).isEqualTo(350_000L); // 300,000 + 50,000 환급
            assertThat(order.getStatus()).isEqualTo(OrderStatus.EXECUTED);
            assertThat(order.getExecPrice()).isEqualTo(65_000L);
            verify(holdingRepository).save(any(Holding.class));
        }

        @Test
        @DisplayName("이미 보유 중인 종목이면 평단가를 가중평균으로 재계산한다")
        void success_recalculatesAveragePrice() {
            account.freezeForOrder(700_000L);
            Order order = pendingOrder(OrderType.BUY, 70_000L, 10);
            when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(order));

            Holding existing = Holding.builder()
                    .account(account)
                    .stockCode(STOCK_CODE)
                    .stockName("삼성전자")
                    .quantity(10)
                    .avgPrice(50_000L)
                    .build();
            when(holdingRepository.findByAccountIdAndStockCode(any(), anyString())).thenReturn(Optional.of(existing));

            orderExecutionService.execute(pendingOrderDto(1L, OrderType.BUY, 70_000L, 10), 70_000L);

            // (50,000*10 + 70,000*10) / 20 = 60,000
            assertThat(existing.getQuantity()).isEqualTo(20);
            assertThat(existing.getAvgPrice()).isEqualTo(60_000L);
            verify(holdingRepository, never()).save(any(Holding.class));
        }

        @Test
        @DisplayName("이미 PENDING이 아닌 주문(중복 체결 재시도)이면 아무 것도 하지 않고 조용히 리턴한다")
        void noop_whenAlreadyProcessed() {
            Order order = pendingOrder(OrderType.BUY, 70_000L, 10);
            order.execute(65_000L); // 다른 스레드가 먼저 체결 완료했다고 가정
            when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(order));

            orderExecutionService.execute(pendingOrderDto(1L, OrderType.BUY, 70_000L, 10), 65_000L);

            verify(holdingRepository, never()).findByAccountIdAndStockCode(any(), anyString());
        }
    }

    @Nested
    @DisplayName("execute() - 매도 체결")
    class ExecuteSell {

        @Test
        @DisplayName("보유수량이 충분하면 정상 체결되고 잔고가 늘어난다")
        void success() {
            Order order = pendingOrder(OrderType.SELL, 60_000L, 5);
            when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(order));

            Holding holding = Holding.builder()
                    .account(account)
                    .stockCode(STOCK_CODE)
                    .stockName("삼성전자")
                    .quantity(10)
                    .avgPrice(50_000L)
                    .build();
            when(holdingRepository.findByAccountIdAndStockCode(any(), anyString())).thenReturn(Optional.of(holding));

            orderExecutionService.execute(pendingOrderDto(1L, OrderType.SELL, 60_000L, 5), 61_000L);

            assertThat(account.getBalance()).isEqualTo(1_305_000L); // 1,000,000 + 61,000*5
            assertThat(holding.getQuantity()).isEqualTo(5);
            assertThat(order.getStatus()).isEqualTo(OrderStatus.EXECUTED);
            verify(holdingRepository, never()).delete(any());
        }

        @Test
        @DisplayName("체결 시점에 보유수량이 부족하면 체결 대신 주문을 취소 처리한다")
        void cancelsInstead_whenHoldingInsufficient() {
            Order order = pendingOrder(OrderType.SELL, 60_000L, 5);
            when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(order));

            Holding holding = Holding.builder()
                    .account(account)
                    .stockCode(STOCK_CODE)
                    .stockName("삼성전자")
                    .quantity(2) // 다른 주문이 먼저 매도해 5주보다 적게 남음
                    .avgPrice(50_000L)
                    .build();
            when(holdingRepository.findByAccountIdAndStockCode(any(), anyString())).thenReturn(Optional.of(holding));

            orderExecutionService.execute(pendingOrderDto(1L, OrderType.SELL, 60_000L, 5), 61_000L);

            assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(account.getBalance()).isEqualTo(1_000_000L); // 잔고 변화 없음
            assertThat(holding.getQuantity()).isEqualTo(2); // 보유수량도 그대로
        }
    }

    @Nested
    @DisplayName("checkAndExecute() - tick 수신 시 대기 주문 일괄 처리")
    class CheckAndExecute {

        @Test
        @DisplayName("매수는 현재가<=지정가, 매도는 현재가>=지정가일 때만 체결하고 Redis에서 제거한다")
        void executesOnlyOrdersMeetingCondition() {
            PendingOrderDto buyMet = pendingOrderDto(1L, OrderType.BUY, 70_000L, 1);       // 현재가 65,000 <= 70,000 → 체결
            PendingOrderDto buyNotMet = pendingOrderDto(2L, OrderType.BUY, 60_000L, 1);     // 65,000 > 60,000 → 미체결
            PendingOrderDto sellMet = pendingOrderDto(3L, OrderType.SELL, 60_000L, 1);      // 65,000 >= 60,000 → 체결
            when(redisPendingOrderService.getPendingOrders(STOCK_CODE))
                    .thenReturn(List.of(buyMet, buyNotMet, sellMet));

            Order order1 = pendingOrder(OrderType.BUY, 70_000L, 1);
            Order order3 = pendingOrder(OrderType.SELL, 60_000L, 1);
            when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(order1));
            when(orderRepository.findByIdForUpdate(3L)).thenReturn(Optional.of(order3));
            when(holdingRepository.findByAccountIdAndStockCode(any(), anyString()))
                    .thenReturn(Optional.of(Holding.builder()
                            .account(account).stockCode(STOCK_CODE).stockName("삼성전자")
                            .quantity(5).avgPrice(50_000L).build()));

            orderExecutionService.checkAndExecute(STOCK_CODE, 65_000L);

            verify(orderRepository).findByIdForUpdate(1L);
            verify(orderRepository, never()).findByIdForUpdate(2L);
            verify(orderRepository).findByIdForUpdate(3L);
            verify(redisPendingOrderService).removePendingOrder(STOCK_CODE, 1L);
            verify(redisPendingOrderService, never()).removePendingOrder(STOCK_CODE, 2L);
            verify(redisPendingOrderService).removePendingOrder(STOCK_CODE, 3L);
        }

        @Test
        @DisplayName("낙관적 락 충돌이 나면 대기 목록에서 지우지 않고 다음 tick 재시도를 위해 남겨둔다")
        void keepsInRedis_onOptimisticLockConflict() {
            PendingOrderDto dto = pendingOrderDto(1L, OrderType.BUY, 70_000L, 1);
            when(redisPendingOrderService.getPendingOrders(STOCK_CODE)).thenReturn(List.of(dto));

            Order order = pendingOrder(OrderType.BUY, 70_000L, 1);
            when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(order));
            when(holdingRepository.findByAccountIdAndStockCode(any(), anyString()))
                    .thenThrow(new ObjectOptimisticLockingFailureException(Order.class, 1L));

            orderExecutionService.checkAndExecute(STOCK_CODE, 65_000L);

            verify(redisPendingOrderService, never()).removePendingOrder(anyString(), anyLong());
        }

        @Test
        @DisplayName("비관적 락 대기 타임아웃(Order 행 경합)이 나도 대기 목록에서 지우지 않고 다음 tick 재시도를 위해 남겨둔다")
        void keepsInRedis_onPessimisticLockTimeout() {
            // execute()가 findByIdForUpdate로 Order 행에 거는 비관적 락을 OrderService.cancelOrder()의
            // 동시 취소 요청이 이미 잡고 있어 락 대기 타임아웃이 나는 상황을 재현한다.
            PendingOrderDto dto = pendingOrderDto(1L, OrderType.BUY, 70_000L, 1);
            when(redisPendingOrderService.getPendingOrders(STOCK_CODE)).thenReturn(List.of(dto));
            when(orderRepository.findByIdForUpdate(1L))
                    .thenThrow(new CannotAcquireLockException("lock wait timeout"));

            orderExecutionService.checkAndExecute(STOCK_CODE, 65_000L);

            verify(redisPendingOrderService, never()).removePendingOrder(anyString(), anyLong());
        }

        @Test
        @DisplayName("CustomException 등 재시도해도 의미 없는 실패는 대기 목록에서 제거한다")
        void removesFromRedis_onCustomException() {
            PendingOrderDto dto = pendingOrderDto(999L, OrderType.BUY, 70_000L, 1);
            when(redisPendingOrderService.getPendingOrders(STOCK_CODE)).thenReturn(List.of(dto));
            when(orderRepository.findByIdForUpdate(999L)).thenReturn(Optional.empty()); // 주문 자체가 사라진 상태

            orderExecutionService.checkAndExecute(STOCK_CODE, 65_000L);

            verify(redisPendingOrderService).removePendingOrder(STOCK_CODE, 999L);
        }

        @Test
        @DisplayName("예상하지 못한 RuntimeException이 나도 대기 목록에서 제거하고 나머지 주문 처리는 계속한다")
        void continuesProcessingOthers_onUnexpectedRuntimeException() {
            PendingOrderDto failing = pendingOrderDto(1L, OrderType.BUY, 70_000L, 1);
            PendingOrderDto succeeding = pendingOrderDto(2L, OrderType.SELL, 60_000L, 1);
            when(redisPendingOrderService.getPendingOrders(STOCK_CODE)).thenReturn(List.of(failing, succeeding));

            Order order1 = pendingOrder(OrderType.BUY, 70_000L, 1);
            Order order2 = pendingOrder(OrderType.SELL, 60_000L, 1);
            when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(order1));
            when(orderRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(order2));
            // 1번 주문은 예상치 못한 런타임 예외(예: 인프라 오류)로 실패, 2번 주문은 정상 체결되는 상황을 재현한다.
            when(holdingRepository.findByAccountIdAndStockCode(any(), anyString()))
                    .thenThrow(new IllegalStateException("예상치 못한 오류"))
                    .thenReturn(Optional.of(Holding.builder()
                            .account(account).stockCode(STOCK_CODE).stockName("삼성전자")
                            .quantity(5).avgPrice(50_000L).build()));

            orderExecutionService.checkAndExecute(STOCK_CODE, 65_000L);

            // 1번 주문은 흡수되어 대기 목록에서 제거되고, 2번 주문 처리는 중단되지 않고 끝까지 진행된다.
            verify(redisPendingOrderService).removePendingOrder(STOCK_CODE, 1L);
            verify(redisPendingOrderService).removePendingOrder(STOCK_CODE, 2L);
            assertThat(order2.getStatus()).isEqualTo(OrderStatus.EXECUTED);
        }

        @Test
        @DisplayName("limitPrice가 null인 대기 주문은 체결 조건 판정을 건너뛰고 대기 목록에 그대로 남긴다")
        void skipsCondition_whenLimitPriceIsNull() {
            PendingOrderDto malformed = PendingOrderDto.builder()
                    .orderId(1L)
                    .accountId(account.getAccountId())
                    .orderType(OrderType.BUY.name())
                    .limitPrice(null)
                    .quantity(1)
                    .stockCode(STOCK_CODE)
                    .stockName("삼성전자")
                    .build();
            when(redisPendingOrderService.getPendingOrders(STOCK_CODE)).thenReturn(List.of(malformed));

            orderExecutionService.checkAndExecute(STOCK_CODE, 65_000L);

            verify(orderRepository, never()).findByIdForUpdate(any());
            verify(redisPendingOrderService, never()).removePendingOrder(anyString(), anyLong());
        }
    }
}
