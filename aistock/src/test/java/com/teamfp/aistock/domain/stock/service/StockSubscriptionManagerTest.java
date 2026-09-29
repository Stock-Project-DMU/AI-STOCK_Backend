package com.teamfp.aistock.domain.stock.service;

import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * StockSubscriptionManager.getActiveSubscribedStockCodes() 검증 테스트(feature/mock-broadcast).
 * MockMarketDataGenerator가 폴링 대상을 정할 때 쓰는 메서드라, 참조 카운트가 0으로 떨어진
 * 종목은 목록에서 빠지는지가 핵심이다.
 */
class StockSubscriptionManagerTest {

    private final StockSubscriptionManager stockSubscriptionManager = new StockSubscriptionManager(Optional.empty());

    @Test
    @DisplayName("아무도 구독하지 않았으면 빈 집합을 반환한다")
    void getActiveSubscribedStockCodes_ReturnsEmpty_WhenNoSubscription() {
        assertThat(stockSubscriptionManager.getActiveSubscribedStockCodes()).isEmpty();
    }

    @Test
    @DisplayName("관심종목/조회 구독 중인 종목코드만 반환한다")
    void getActiveSubscribedStockCodes_ReturnsOnlyActiveCodes() {
        stockSubscriptionManager.increaseWatchlistSubscription("005930");
        stockSubscriptionManager.increaseViewingSubscription("000660");

        assertThat(stockSubscriptionManager.getActiveSubscribedStockCodes()).isEqualTo(Set.of("005930", "000660"));
    }

    @Test
    @DisplayName("구독을 모두 해제해 참조 카운트가 0이 되면 목록에서 빠진다")
    void getActiveSubscribedStockCodes_ExcludesFullyUnsubscribedCodes() {
        stockSubscriptionManager.increaseViewingSubscription("005930");
        stockSubscriptionManager.decreaseViewingSubscription("005930");

        assertThat(stockSubscriptionManager.getActiveSubscribedStockCodes()).isEmpty();
    }
}
