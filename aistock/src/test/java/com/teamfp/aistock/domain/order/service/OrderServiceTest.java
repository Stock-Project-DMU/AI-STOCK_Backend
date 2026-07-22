package com.teamfp.aistock.domain.order.service;

import java.time.LocalDate;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.teamfp.aistock.domain.account.entity.Account;
import com.teamfp.aistock.domain.account.repository.AccountRepository;
import com.teamfp.aistock.domain.order.dto.request.CreateOrderRequest;
import com.teamfp.aistock.domain.order.dto.response.CreateOrderResponse;
import com.teamfp.aistock.domain.order.entity.Holding;
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
import com.teamfp.aistock.global.redis.RedisStockCacheService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * feature/order-market — OrderService.createMarketOrder() 단위 테스트.
 *
 * DB/Redis/Auth 등 다른 브랜치(feature/auth-login, feature/account-mypage,
 * feature/stock-price)가 아직 스텁이라 서버를 띄우는 통합 테스트는 지금 불가능하다.
 * 대신 Repository/RedisStockCacheService를 Mockito로 모킹해서 OrderService의
 * 잔고 검증-체결-보유종목 갱신 로직만 독립적으로 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private HoldingRepository holdingRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private RedisStockCacheService redisStockCacheService;

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

        // 시작 잔고 100만원짜리 계좌
        account = Account.builder()
                .user(user)
                .accountName("테스트계좌")
                .accountNumber("ACC-0001")
                .openedAt(LocalDate.now())
                .baseBalance(1_000_000L)
                .balance(1_000_000L)
                .build();

        // ACCOUNT_NOT_FOUND 테스트처럼 이 stub을 쓰지 않는 케이스도 있어 lenient로 등록한다
        // (안 그러면 MockitoExtension의 strict stubbing 검사가 UnnecessaryStubbingException을 던진다).
        Mockito.lenient().when(accountRepository.findByAccountIdAndUserId(ACCOUNT_ID, USER_ID)).thenReturn(Optional.of(account));

        // HoldingSettlementService는 @Mock이 아니라 실제 구현을 그대로 쓴다 — 이 테스트들이
        // 검증하는 "보유종목 수량/평단가가 실제로 어떻게 바뀌는지"는 그 서비스 내부 로직이라,
        // 모킹해버리면 여기서 검증할 대상이 사라진다. 대신 그 서비스가 의존하는
        // holdingRepository는 이미 모킹돼 있는 것을 그대로 재사용한다.
        ReflectionTestUtils.setField(orderService, "holdingSettlementService", new HoldingSettlementService(holdingRepository));
    }

    private StockPriceDto priceOf(long currentPrice) {
        return StockPriceDto.builder()
                .stockCode(STOCK_CODE)
                .stockName("삼성전자")
                .currentPrice(currentPrice)
                .build();
    }

    private CreateOrderRequest requestOf(OrderType orderType, int quantity) {
        return new CreateOrderRequest(ACCOUNT_ID, STOCK_CODE, orderType, quantity, PriceType.MARKET, 0L);
    }

    @Nested
    @DisplayName("매수 주문")
    class Buy {

        @Test
        @DisplayName("잔고가 충분하면 즉시 체결되고, 처음 매수하는 종목이면 보유종목이 새로 생긴다")
        void buy_success_createsNewHolding() {
            when(redisStockCacheService.getStockPrice(STOCK_CODE)).thenReturn(priceOf(70_000L));
            when(holdingRepository.findByAccountIdAndStockCode(any(), anyString())).thenReturn(Optional.empty());

            CreateOrderResponse response = orderService.createMarketOrder(USER_ID, requestOf(OrderType.BUY, 10));

            // 70,000원 * 10주 = 700,000원 차감 → 잔고 300,000원 남아야 함
            assertThat(account.getBalance()).isEqualTo(300_000L);
            assertThat(response.execPrice()).isEqualTo(70_000L);
            assertThat(response.quantity()).isEqualTo(10);
            assertThat(response.status()).isEqualTo(OrderStatus.EXECUTED);

            verify(holdingRepository).save(any(Holding.class));
            verify(orderRepository).save(any());
        }

        @Test
        @DisplayName("이미 보유 중인 종목을 추가 매수하면 평단가가 가중평균으로 재계산된다")
        void buy_success_recalculatesAveragePrice() {
            Holding existing = Holding.builder()
                    .account(account)
                    .stockCode(STOCK_CODE)
                    .stockName("삼성전자")
                    .quantity(10)
                    .avgPrice(1_000L)
                    .build();
            when(redisStockCacheService.getStockPrice(STOCK_CODE)).thenReturn(priceOf(2_000L));
            when(holdingRepository.findByAccountIdAndStockCode(any(), anyString())).thenReturn(Optional.of(existing));

            orderService.createMarketOrder(USER_ID, requestOf(OrderType.BUY, 5));

            // (1000*10 + 2000*5) / 15 = 1333 (원단위 내림)
            assertThat(existing.getQuantity()).isEqualTo(15);
            assertThat(existing.getAvgPrice()).isEqualTo(1_333L);
            verify(holdingRepository, never()).save(any(Holding.class)); // 신규 생성이 아니라 기존 엔티티 갱신(더티체킹)
        }

        @Test
        @DisplayName("잔고가 부족하면 INSUFFICIENT_BALANCE 예외를 던지고 체결하지 않는다")
        void buy_fail_insufficientBalance() {
            when(redisStockCacheService.getStockPrice(STOCK_CODE)).thenReturn(priceOf(200_000L)); // 10주 = 2,000,000원 (잔고 초과)

            assertThatThrownBy(() -> orderService.createMarketOrder(USER_ID, requestOf(OrderType.BUY, 10)))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INSUFFICIENT_BALANCE);

            assertThat(account.getBalance()).isEqualTo(1_000_000L); // 잔고 그대로
            verify(orderRepository, never()).save(any());
        }

        @Test
        @DisplayName("같은 종목 최초 매수 요청이 동시에 들어와 유니크 제약을 위반하면 OPTIMISTIC_LOCK_CONFLICT 예외로 변환한다")
        void buy_fail_concurrentFirstBuyRace() {
            // findHolding()의 비관적 락은 이미 존재하는 행만 잠그므로, 두 요청이 동시에 빈 값을
            // 본 뒤 각자 INSERT를 시도하는 경합은 holdings.uq_account_stock 유니크 제약으로만
            // 걸러진다. 이 케이스를 재현하기 위해 save()가 DataIntegrityViolationException을
            // 던지도록 모킹한다.
            when(redisStockCacheService.getStockPrice(STOCK_CODE)).thenReturn(priceOf(70_000L));
            when(holdingRepository.findByAccountIdAndStockCode(any(), anyString())).thenReturn(Optional.empty());
            when(holdingRepository.save(any(Holding.class)))
                    .thenThrow(new org.springframework.dao.DataIntegrityViolationException("uq_account_stock"));

            assertThatThrownBy(() -> orderService.createMarketOrder(USER_ID, requestOf(OrderType.BUY, 10)))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.OPTIMISTIC_LOCK_CONFLICT);
        }
    }

    @Nested
    @DisplayName("매도 주문")
    class Sell {

        @Test
        @DisplayName("보유수량이 충분하면 즉시 체결되고 잔고가 늘어난다")
        void sell_success() {
            Holding existing = Holding.builder()
                    .account(account)
                    .stockCode(STOCK_CODE)
                    .stockName("삼성전자")
                    .quantity(10)
                    .avgPrice(50_000L)
                    .build();
            when(redisStockCacheService.getStockPrice(STOCK_CODE)).thenReturn(priceOf(60_000L));
            when(holdingRepository.findByAccountIdAndStockCode(any(), anyString())).thenReturn(Optional.of(existing));

            CreateOrderResponse response = orderService.createMarketOrder(USER_ID, requestOf(OrderType.SELL, 4));

            // 60,000 * 4 = 240,000원 입금 → 잔고 1,240,000원
            assertThat(account.getBalance()).isEqualTo(1_240_000L);
            assertThat(existing.getQuantity()).isEqualTo(6);
            assertThat(response.status()).isEqualTo(OrderStatus.EXECUTED);
            verify(holdingRepository, never()).delete(any());
        }

        @Test
        @DisplayName("전량 매도하면 보유종목 행이 삭제된다")
        void sell_success_deletesHoldingWhenFullyLiquidated() {
            Holding existing = Holding.builder()
                    .account(account)
                    .stockCode(STOCK_CODE)
                    .stockName("삼성전자")
                    .quantity(5)
                    .avgPrice(50_000L)
                    .build();
            when(redisStockCacheService.getStockPrice(STOCK_CODE)).thenReturn(priceOf(60_000L));
            when(holdingRepository.findByAccountIdAndStockCode(any(), anyString())).thenReturn(Optional.of(existing));

            orderService.createMarketOrder(USER_ID, requestOf(OrderType.SELL, 5));

            assertThat(existing.getQuantity()).isEqualTo(0);
            verify(holdingRepository, times(1)).delete(existing);
        }

        @Test
        @DisplayName("보유하지 않은 종목을 매도하려 하면 INSUFFICIENT_HOLDING 예외를 던진다")
        void sell_fail_noHolding() {
            when(redisStockCacheService.getStockPrice(STOCK_CODE)).thenReturn(priceOf(60_000L));
            when(holdingRepository.findByAccountIdAndStockCode(any(), anyString())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> orderService.createMarketOrder(USER_ID, requestOf(OrderType.SELL, 1)))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INSUFFICIENT_HOLDING);
        }

        @Test
        @DisplayName("보유수량보다 많이 매도하려 하면 INSUFFICIENT_HOLDING 예외를 던진다")
        void sell_fail_insufficientHolding() {
            Holding existing = Holding.builder()
                    .account(account)
                    .stockCode(STOCK_CODE)
                    .stockName("삼성전자")
                    .quantity(3)
                    .avgPrice(50_000L)
                    .build();
            when(redisStockCacheService.getStockPrice(STOCK_CODE)).thenReturn(priceOf(60_000L));
            when(holdingRepository.findByAccountIdAndStockCode(any(), anyString())).thenReturn(Optional.of(existing));

            assertThatThrownBy(() -> orderService.createMarketOrder(USER_ID, requestOf(OrderType.SELL, 4)))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INSUFFICIENT_HOLDING);
        }
    }

    @Nested
    @DisplayName("공통 예외 상황")
    class CommonFailures {

        @Test
        @DisplayName("계좌가 없으면 ACCOUNT_NOT_FOUND 예외를 던진다")
        void fail_accountNotFound() {
            when(accountRepository.findByAccountIdAndUserId(anyLong(), anyLong())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> orderService.createMarketOrder(999L, requestOf(OrderType.BUY, 1)))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.ACCOUNT_NOT_FOUND);
        }

        @Test
        @DisplayName("현재가 캐시가 없으면(TTL 만료) STOCK_PRICE_NOT_AVAILABLE 예외를 던진다")
        void fail_priceCacheMissing() {
            when(redisStockCacheService.getStockPrice(STOCK_CODE)).thenReturn(null);

            assertThatThrownBy(() -> orderService.createMarketOrder(USER_ID, requestOf(OrderType.BUY, 1)))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.STOCK_PRICE_NOT_AVAILABLE);

            verify(orderRepository, never()).save(any());
        }

        @Test
        @DisplayName("계좌가 정지 상태면 ACCOUNT_SUSPENDED 예외를 던지고 시세 조회조차 하지 않는다")
        void fail_accountSuspended() {
            account.suspend();

            assertThatThrownBy(() -> orderService.createMarketOrder(USER_ID, requestOf(OrderType.BUY, 1)))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.ACCOUNT_SUSPENDED);

            verify(redisStockCacheService, never()).getStockPrice(anyString());
            verify(orderRepository, never()).save(any());
        }
    }
}
