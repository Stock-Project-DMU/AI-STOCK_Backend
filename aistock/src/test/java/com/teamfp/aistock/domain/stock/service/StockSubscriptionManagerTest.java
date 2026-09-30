package com.teamfp.aistock.domain.stock.service;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.teamfp.aistock.global.redis.RedisPendingOrderService;
import com.teamfp.aistock.infra.marketdata.MarketDataWebSocketClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * StockSubscriptionManager 검증 테스트. getActiveSubscribedStockCodes()는 MockMarketDataGenerator가
 * 폴링 대상을 정할 때 쓰는 메서드라, 참조 카운트가 0으로 떨어진 종목은 목록에서 빠지는지가 핵심이다.
 * 미체결 주문(order) 출처는 상세페이지를 떠나도 구독이 유지되는지, 서버 기동 시 복원되는지를 본다.
 */
class StockSubscriptionManagerTest {

    private final RedisPendingOrderService redisPendingOrderService = mock(RedisPendingOrderService.class);
    private final StockSubscriptionManager stockSubscriptionManager =
            new StockSubscriptionManager(Optional.empty(), redisPendingOrderService);

    @Test
    @DisplayName("아무도 구독하지 않았으면 빈 집합을 반환한다")
    void getActiveSubscribedStockCodes_ReturnsEmpty_WhenNoSubscription() {
        assertThat(stockSubscriptionManager.getActiveSubscribedStockCodes()).isEmpty();
    }

    @Test
    @DisplayName("관심종목/조회/미체결 주문 구독 중인 종목코드만 반환한다")
    void getActiveSubscribedStockCodes_ReturnsOnlyActiveCodes() {
        stockSubscriptionManager.increaseWatchlistSubscription("005930");
        stockSubscriptionManager.increaseViewingSubscription("000660");
        stockSubscriptionManager.increaseOrderSubscription("035420");

        assertThat(stockSubscriptionManager.getActiveSubscribedStockCodes()).isEqualTo(Set.of("005930", "000660", "035420"));
    }

    @Test
    @DisplayName("구독을 모두 해제해 참조 카운트가 0이 되면 목록에서 빠진다")
    void getActiveSubscribedStockCodes_ExcludesFullyUnsubscribedCodes() {
        stockSubscriptionManager.increaseViewingSubscription("005930");
        stockSubscriptionManager.decreaseViewingSubscription("005930");

        assertThat(stockSubscriptionManager.getActiveSubscribedStockCodes()).isEmpty();
    }

    @Nested
    @DisplayName("미체결 주문 종목 구독")
    class OrderSubscription {

        @Test
        @DisplayName("상세페이지를 떠나도 미체결 주문이 남아 있으면 구독이 유지된다")
        void keepsSubscription_WhenViewerLeavesButOrderPending() {
            stockSubscriptionManager.increaseViewingSubscription("005930");
            stockSubscriptionManager.increaseOrderSubscription("005930");

            stockSubscriptionManager.decreaseViewingSubscription("005930");

            assertThat(stockSubscriptionManager.getActiveSubscribedStockCodes()).containsExactly("005930");
        }

        @Test
        @DisplayName("미체결 주문이 모두 체결·취소되면 구독이 해제된다")
        void releasesSubscription_WhenAllOrdersRemoved() {
            stockSubscriptionManager.increaseOrderSubscription("005930");
            stockSubscriptionManager.increaseOrderSubscription("005930");

            stockSubscriptionManager.decreaseOrderSubscription("005930");
            assertThat(stockSubscriptionManager.getActiveSubscribedStockCodes()).containsExactly("005930");

            stockSubscriptionManager.decreaseOrderSubscription("005930");
            assertThat(stockSubscriptionManager.getActiveSubscribedStockCodes()).isEmpty();
        }

        @Test
        @DisplayName("서버 기동 시 재적재된 미체결 주문 건수만큼 구독을 복원한다")
        void restoreOrderSubscriptions_RestoresFromPendingOrderCounts() {
            when(redisPendingOrderService.countPendingOrdersByStockCode()).thenReturn(Map.of("005930", 2L, "000660", 1L));

            stockSubscriptionManager.restoreOrderSubscriptions();

            assertThat(stockSubscriptionManager.getActiveSubscribedStockCodes()).isEqualTo(Set.of("005930", "000660"));
            // 2건이 복원됐으므로 한 건만 체결돼서는 구독이 풀리지 않는다.
            stockSubscriptionManager.decreaseOrderSubscription("005930");
            assertThat(stockSubscriptionManager.getActiveSubscribedStockCodes()).contains("005930");
        }

        @Test
        @DisplayName("실 모드에서는 첫 주문에만 외부 시세 구독, 마지막 주문 제거 시에만 구독 해제를 호출한다")
        void realMode_SubscribesOnFirstOrderAndUnsubscribesOnLast() {
            MarketDataWebSocketClient webSocketClient = mock(MarketDataWebSocketClient.class);
            StockSubscriptionManager realModeManager =
                    new StockSubscriptionManager(Optional.of(webSocketClient), redisPendingOrderService);

            realModeManager.increaseOrderSubscription("005930");
            realModeManager.increaseOrderSubscription("005930");
            realModeManager.decreaseOrderSubscription("005930");

            verify(webSocketClient, times(1)).subscribe("005930");
            verify(webSocketClient, never()).unsubscribe("005930");

            realModeManager.decreaseOrderSubscription("005930");

            verify(webSocketClient, times(1)).unsubscribe("005930");
        }
    }
}
