package com.teamfp.aistock.domain.order.service;

import java.time.LocalDate;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.teamfp.aistock.domain.account.entity.Account;
import com.teamfp.aistock.domain.account.repository.AccountRepository;
import com.teamfp.aistock.domain.order.dto.PendingOrderDto;
import com.teamfp.aistock.domain.order.dto.request.CreateOrderRequest;
import com.teamfp.aistock.domain.order.dto.response.CreateOrderResponse;
import com.teamfp.aistock.domain.order.entity.Holding;
import com.teamfp.aistock.domain.order.entity.Order;
import com.teamfp.aistock.domain.order.entity.OrderStatus;
import com.teamfp.aistock.domain.order.entity.OrderType;
import com.teamfp.aistock.domain.order.entity.PriceType;
import com.teamfp.aistock.domain.order.repository.HoldingRepository;
import com.teamfp.aistock.domain.order.repository.OrderRepository;
import com.teamfp.aistock.domain.stock.dto.StockPriceDto;
import com.teamfp.aistock.domain.user.entity.Role;
import com.teamfp.aistock.domain.user.entity.User;
import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;
import com.teamfp.aistock.global.redis.RedisPendingOrderService;
import com.teamfp.aistock.global.redis.RedisStockCacheService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * feature/order-limit — OrderService.createLimitOrder()/cancelOrder() 단위 테스트.
 *
 * OrderServiceTest(order-market)와 마찬가지로 다른 브랜치가 아직 스텁이라 Repository/Redis 서비스를
 * Mockito로 모킹해서 잔고 동결(frozen_balance)-PENDING 등록-취소 로직만 독립적으로 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceLimitOrderTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private HoldingRepository holdingRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private RedisStockCacheService redisStockCacheService;

    @Mock
    private RedisPendingOrderService redisPendingOrderService;

    @InjectMocks
    private OrderService orderService;

    private static final Long USER_ID = 1L;
    private static final Long ACCOUNT_ID = 100L;
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
        // cancelOrder()는 order.getAccount().getUser().getUserId()로 소유권을 검증하므로,
        // 실제 DB 없이 만든 User라도 userId를 채워둬야 한다(자동 생성 PK라 builder에는 없음).
        ReflectionTestUtils.setField(user, "userId", USER_ID);

        account = Account.builder()
                .user(user)
                .accountName("테스트계좌")
                .accountNumber("ACC-0001")
                .openedAt(LocalDate.now())
                .baseBalance(1_000_000L)
                .balance(1_000_000L)
                .build();

        Mockito.lenient().when(accountRepository.findByAccountIdAndUserId(ACCOUNT_ID, USER_ID)).thenReturn(Optional.of(account));
        // orderRepository.save()는 실제 DB처럼 orderId를 채워주지 않으므로, 테스트에서는
        // Order.builder()로 만든 엔티티를 그대로 리턴해도 orderId가 null이라 뒤 로직(Redis 등록)
        // 검증에는 지장이 없다 — orderId 자체를 검증하는 케이스는 없다.
        Mockito.lenient().when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private CreateOrderRequest limitRequestOf(OrderType orderType, int quantity, long orderPrice) {
        return new CreateOrderRequest(ACCOUNT_ID, STOCK_CODE, orderType, quantity, PriceType.LIMIT, orderPrice);
    }

    private Order pendingBuyOrder(long orderPrice, int quantity) {
        return Order.builder()
                .account(account)
                .stockCode(STOCK_CODE)
                .stockName("삼성전자")
                .orderType(OrderType.BUY)
                .priceType(PriceType.LIMIT)
                .orderPrice(orderPrice)
                .quantity(quantity)
                .build();
    }

    @Nested
    @DisplayName("공통 예외 상황")
    class CommonFailures {

        @Test
        @DisplayName("계좌가 정지 상태면 지정가 주문 등록 시 ACCOUNT_SUSPENDED 예외를 던지고 아무것도 등록/동결하지 않는다")
        void fail_accountSuspended_createLimitOrder() {
            account.suspend();

            assertThatThrownBy(() -> orderService.createLimitOrder(USER_ID, limitRequestOf(OrderType.BUY, 1, 70_000L)))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.ACCOUNT_SUSPENDED);

            assertThat(account.getFrozenBalance()).isZero();
            verify(orderRepository, never()).save(any());
            verify(redisPendingOrderService, never()).addPendingOrder(anyString(), any());
        }

        @Test
        @DisplayName("계좌가 정지 상태면 주문 취소도 거래 행위로 간주해 ACCOUNT_SUSPENDED 예외를 던진다")
        void fail_accountSuspended_cancelOrder() {
            account.suspend();
            Order order = pendingBuyOrder(70_000L, 10);
            when(orderRepository.findByOrderIdAndUserIdForUpdate(1L, USER_ID)).thenReturn(Optional.of(order));

            assertThatThrownBy(() -> orderService.cancelOrder(USER_ID, 1L))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.ACCOUNT_SUSPENDED);

            verify(redisPendingOrderService, never()).removePendingOrder(anyString(), anyLong());
        }
    }

    @Nested
    @DisplayName("지정가 매수 주문 등록")
    class CreateLimitBuyOrder {

        @Test
        @DisplayName("잔고가 충분하면 주문금액만큼 frozenBalance로 묶고 PENDING 주문을 Redis에도 등록한다")
        void success_freezesBalanceAndRegistersPendingOrder() {
            when(redisStockCacheService.getStockPrice(STOCK_CODE)).thenReturn(StockPriceDto.builder()
                    .stockCode(STOCK_CODE)
                    .stockName("삼성전자")
                    .currentPrice(65_000L)
                    .build());

            CreateOrderResponse response = orderService.createLimitOrder(USER_ID, limitRequestOf(OrderType.BUY, 10, 70_000L));

            // 70,000원 * 10주 = 700,000원이 balance에서 frozenBalance로 이동
            assertThat(account.getBalance()).isEqualTo(300_000L);
            assertThat(account.getFrozenBalance()).isEqualTo(700_000L);
            assertThat(response.status()).isEqualTo(OrderStatus.PENDING);
            assertThat(response.execPrice()).isNull();

            ArgumentCaptor<PendingOrderDto> captor = ArgumentCaptor.forClass(PendingOrderDto.class);
            verify(redisPendingOrderService).addPendingOrder(eq(STOCK_CODE), captor.capture());
            assertThat(captor.getValue().getLimitPrice()).isEqualTo(70_000L);
            assertThat(captor.getValue().getOrderType()).isEqualTo("BUY");
            assertThat(captor.getValue().getStockName()).isEqualTo("삼성전자");
        }

        @Test
        @DisplayName("주문금액이 잔고를 초과하면 INSUFFICIENT_BALANCE 예외를 던지고 아무것도 동결하지 않는다")
        void fail_insufficientBalance() {
            assertThatThrownBy(() -> orderService.createLimitOrder(USER_ID, limitRequestOf(OrderType.BUY, 100, 70_000L)))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INSUFFICIENT_BALANCE);

            assertThat(account.getBalance()).isEqualTo(1_000_000L);
            assertThat(account.getFrozenBalance()).isZero();
            verify(orderRepository, never()).save(any());
            verify(redisPendingOrderService, never()).addPendingOrder(anyString(), any());
        }

        @Test
        @DisplayName("주문가가 0 이하면 INVALID_INPUT 예외를 던진다")
        void fail_invalidOrderPrice() {
            assertThatThrownBy(() -> orderService.createLimitOrder(USER_ID, limitRequestOf(OrderType.BUY, 1, 0L)))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_INPUT);
        }

        @Test
        @DisplayName("현재가 캐시가 없으면(신규 종목 이름 조회 불가) STOCK_PRICE_NOT_AVAILABLE 예외를 던진다")
        void fail_priceCacheMissingForStockName() {
            when(redisStockCacheService.getStockPrice(STOCK_CODE)).thenReturn(null);

            assertThatThrownBy(() -> orderService.createLimitOrder(USER_ID, limitRequestOf(OrderType.BUY, 1, 70_000L)))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.STOCK_PRICE_NOT_AVAILABLE);

            assertThat(account.getFrozenBalance()).isZero();
        }

        @Test
        @DisplayName("이미 보유 중인 종목이면 현재가 캐시가 비어 있어도 holdings의 종목명으로 등록된다")
        void success_usesHoldingStockNameWhenPriceCacheMissing() {
            Holding holding = Holding.builder()
                    .account(account)
                    .stockCode(STOCK_CODE)
                    .stockName("삼성전자")
                    .quantity(10)
                    .avgPrice(50_000L)
                    .build();
            when(holdingRepository.findByAccountIdAndStockCode(any(), anyString())).thenReturn(Optional.of(holding));

            CreateOrderResponse response = orderService.createLimitOrder(USER_ID, limitRequestOf(OrderType.BUY, 1, 70_000L));

            assertThat(response.status()).isEqualTo(OrderStatus.PENDING);
            assertThat(account.getFrozenBalance()).isEqualTo(70_000L);
            verify(redisStockCacheService, never()).getStockPrice(anyString());

            ArgumentCaptor<PendingOrderDto> captor = ArgumentCaptor.forClass(PendingOrderDto.class);
            verify(redisPendingOrderService).addPendingOrder(eq(STOCK_CODE), captor.capture());
            assertThat(captor.getValue().getStockName()).isEqualTo("삼성전자");
        }
    }

    @Nested
    @DisplayName("지정가 매도 주문 등록")
    class CreateLimitSellOrder {

        @Test
        @DisplayName("보유수량이 충분하면 frozenBalance를 쓰지 않고 PENDING 주문을 등록한다")
        void success_doesNotFreezeBalance() {
            Holding holding = Holding.builder()
                    .account(account)
                    .stockCode(STOCK_CODE)
                    .stockName("삼성전자")
                    .quantity(10)
                    .avgPrice(50_000L)
                    .build();
            when(holdingRepository.findByAccountIdAndStockCode(any(), anyString())).thenReturn(Optional.of(holding));

            CreateOrderResponse response = orderService.createLimitOrder(USER_ID, limitRequestOf(OrderType.SELL, 5, 80_000L));

            assertThat(account.getFrozenBalance()).isZero();
            assertThat(account.getBalance()).isEqualTo(1_000_000L);
            assertThat(response.status()).isEqualTo(OrderStatus.PENDING);
            verify(redisPendingOrderService).addPendingOrder(eq(STOCK_CODE), any(PendingOrderDto.class));
        }

        @Test
        @DisplayName("보유수량보다 많이 팔려고 하면 INSUFFICIENT_HOLDING 예외를 던진다")
        void fail_insufficientHolding() {
            Holding holding = Holding.builder()
                    .account(account)
                    .stockCode(STOCK_CODE)
                    .stockName("삼성전자")
                    .quantity(3)
                    .avgPrice(50_000L)
                    .build();
            when(holdingRepository.findByAccountIdAndStockCode(any(), anyString())).thenReturn(Optional.of(holding));

            assertThatThrownBy(() -> orderService.createLimitOrder(USER_ID, limitRequestOf(OrderType.SELL, 5, 80_000L)))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INSUFFICIENT_HOLDING);
        }

        @Test
        @DisplayName("보유하지 않은 종목이면 INSUFFICIENT_HOLDING 예외를 던진다")
        void fail_noHolding() {
            when(holdingRepository.findByAccountIdAndStockCode(any(), anyString())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> orderService.createLimitOrder(USER_ID, limitRequestOf(OrderType.SELL, 1, 80_000L)))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INSUFFICIENT_HOLDING);
        }

        @Test
        @DisplayName("이미 등록해둔 PENDING 매도 주문 수량을 빼고도 부족하면 INSUFFICIENT_HOLDING 예외를 던진다")
        void fail_insufficientHolding_afterSubtractingAlreadyPendingSellQuantity() {
            // 10주를 보유 중인데 이미 다른 지정가 매도 주문으로 7주가 대기 중이라면,
            // 실제 추가로 팔 수 있는 수량은 3주뿐이다.
            Holding holding = Holding.builder()
                    .account(account)
                    .stockCode(STOCK_CODE)
                    .stockName("삼성전자")
                    .quantity(10)
                    .avgPrice(50_000L)
                    .build();
            when(holdingRepository.findByAccountIdAndStockCode(any(), anyString())).thenReturn(Optional.of(holding));
            when(orderRepository.sumPendingSellQuantity(any(), eq(STOCK_CODE))).thenReturn(7);

            assertThatThrownBy(() -> orderService.createLimitOrder(USER_ID, limitRequestOf(OrderType.SELL, 5, 80_000L)))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INSUFFICIENT_HOLDING);

            verify(orderRepository, never()).save(any());
        }

        @Test
        @DisplayName("이미 등록해둔 PENDING 매도 주문 수량을 빼고도 충분하면 정상 등록된다")
        void success_afterSubtractingAlreadyPendingSellQuantity() {
            Holding holding = Holding.builder()
                    .account(account)
                    .stockCode(STOCK_CODE)
                    .stockName("삼성전자")
                    .quantity(10)
                    .avgPrice(50_000L)
                    .build();
            when(holdingRepository.findByAccountIdAndStockCode(any(), anyString())).thenReturn(Optional.of(holding));
            when(orderRepository.sumPendingSellQuantity(any(), eq(STOCK_CODE))).thenReturn(4);

            CreateOrderResponse response = orderService.createLimitOrder(USER_ID, limitRequestOf(OrderType.SELL, 6, 80_000L));

            assertThat(response.status()).isEqualTo(OrderStatus.PENDING);
        }
    }

    @Nested
    @DisplayName("지정가 주문 취소")
    class CancelOrder {

        @Test
        @DisplayName("PENDING 매수 주문을 취소하면 frozenBalance가 balance로 되돌아오고 CANCELLED로 바뀐다")
        void success_unfreezesBalance() {
            Order order = pendingBuyOrder(70_000L, 10);
            account.freezeForOrder(700_000L); // 주문 등록 시점에 이미 동결돼 있던 상태를 재현
            when(orderRepository.findByOrderIdAndUserIdForUpdate(1L, USER_ID)).thenReturn(Optional.of(order));

            orderService.cancelOrder(USER_ID, 1L);

            assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(account.getFrozenBalance()).isZero();
            assertThat(account.getBalance()).isEqualTo(1_000_000L);
            verify(redisPendingOrderService).removePendingOrder(STOCK_CODE, order.getOrderId());
        }

        @Test
        @DisplayName("존재하지 않거나 내 계좌 소유가 아닌 주문이면 ORDER_NOT_FOUND 예외를 던진다")
        void fail_orderNotFound() {
            when(orderRepository.findByOrderIdAndUserIdForUpdate(anyLong(), anyLong())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> orderService.cancelOrder(USER_ID, 999L))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.ORDER_NOT_FOUND);
        }

        @Test
        @DisplayName("이미 체결되었거나 취소된 주문을 다시 취소하려 하면 ORDER_ALREADY_PROCESSED 예외를 던진다")
        void fail_alreadyProcessed() {
            Order order = pendingBuyOrder(70_000L, 10);
            order.execute(65_000L);
            when(orderRepository.findByOrderIdAndUserIdForUpdate(1L, USER_ID)).thenReturn(Optional.of(order));

            assertThatThrownBy(() -> orderService.cancelOrder(USER_ID, 1L))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.ORDER_ALREADY_PROCESSED);

            verify(redisPendingOrderService, never()).removePendingOrder(anyString(), anyLong());
        }
    }
}
