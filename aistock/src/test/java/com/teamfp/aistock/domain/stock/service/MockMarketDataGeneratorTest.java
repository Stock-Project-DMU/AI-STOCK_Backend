package com.teamfp.aistock.domain.stock.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.teamfp.aistock.infra.marketdata.LocalMarketDataReader;
import com.teamfp.aistock.infra.marketdata.MarketDataListener;
import com.teamfp.aistock.infra.marketdata.dto.CurrentPriceDetailDto;
import com.teamfp.aistock.infra.marketdata.dto.HogaData;
import com.teamfp.aistock.infra.marketdata.dto.TickData;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MockMarketDataGenerator 검증 테스트(feature/mock-broadcast, KNOWN_ISSUES.md 2번 해소).
 * market_data.json의 updatedAt 변경 여부에 따라 구독 중인 종목만 MarketDataListener에
 * 전달되는지, 변경이 없으면 중복 브로드캐스트가 생략되는지 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class MockMarketDataGeneratorTest {

    private static final String STOCK_CODE = "005930";

    @Mock
    private LocalMarketDataReader localMarketDataReader;

    @Mock
    private StockSubscriptionManager stockSubscriptionManager;

    @Mock
    private MarketDataListener listener;

    private MockMarketDataGenerator mockMarketDataGenerator;

    @BeforeEach
    void setUp() {
        mockMarketDataGenerator = new MockMarketDataGenerator(localMarketDataReader, stockSubscriptionManager, List.of(listener));
    }

    @Test
    @DisplayName("구독 중인 종목이 없으면 파일을 읽지 않고 아무것도 브로드캐스트하지 않는다")
    void pollAndBroadcast_NoSubscription_DoesNothing() {
        when(stockSubscriptionManager.getActiveSubscribedStockCodes()).thenReturn(Set.of());

        mockMarketDataGenerator.pollAndBroadcast();

        verify(localMarketDataReader, never()).getAllCurrentPrices();
        verify(listener, never()).onTickReceived(any());
        verify(listener, never()).onHogaReceived(any());
    }

    @Test
    @DisplayName("구독 중인 종목의 updatedAt이 바뀌었으면 tick과 호가를 모두 브로드캐스트한다")
    void pollAndBroadcast_ChangedUpdatedAt_BroadcastsTickAndHoga() {
        LocalDateTime updatedAt = LocalDateTime.of(2026, 9, 21, 12, 0, 0);
        CurrentPriceDetailDto price = CurrentPriceDetailDto.builder()
                .stockCode(STOCK_CODE)
                .stockName("삼성전자")
                .currentPrice(78500L)
                .changeAmount(-500L)
                .changeRate(-0.64)
                .volume(12345678L)
                .updatedAt(updatedAt)
                .build();
        HogaData hoga = HogaData.builder().stockCode(STOCK_CODE).askPrices(List.of(78600L)).build();

        when(stockSubscriptionManager.getActiveSubscribedStockCodes()).thenReturn(Set.of(STOCK_CODE));
        when(localMarketDataReader.getAllCurrentPrices()).thenReturn(Map.of(STOCK_CODE, price));
        when(localMarketDataReader.getHoga(STOCK_CODE)).thenReturn(Optional.of(hoga));

        mockMarketDataGenerator.pollAndBroadcast();

        ArgumentCaptor<TickData> tickCaptor = ArgumentCaptor.forClass(TickData.class);
        verify(listener).onTickReceived(tickCaptor.capture());
        assertThat(tickCaptor.getValue().getStockCode()).isEqualTo(STOCK_CODE);
        assertThat(tickCaptor.getValue().getStockName()).isEqualTo("삼성전자");
        // CurrentPriceDetailDto.changeAmount(-500, 부호 있음)는 TickData.changeAmount(부호
        // 없는 절대값)로 옮길 때 절대값으로 변환돼야 한다 — 그대로 옮기면 StockBroadcastService가
        // changeRate 부호로 다시 부호를 적용해 이중 반전되기 때문.
        assertThat(tickCaptor.getValue().getChangeAmount()).isEqualTo(500L);

        verify(listener).onHogaReceived(hoga);
    }

    @Test
    @DisplayName("같은 updatedAt이 연속으로 조회되면 두 번째 폴링에서는 브로드캐스트를 생략한다")
    void pollAndBroadcast_SameUpdatedAt_SkipsDuplicateBroadcast() {
        LocalDateTime updatedAt = LocalDateTime.of(2026, 9, 21, 12, 0, 0);
        CurrentPriceDetailDto price = CurrentPriceDetailDto.builder()
                .stockCode(STOCK_CODE)
                .currentPrice(78500L)
                .changeAmount(500L)
                .changeRate(0.64)
                .updatedAt(updatedAt)
                .build();

        when(stockSubscriptionManager.getActiveSubscribedStockCodes()).thenReturn(Set.of(STOCK_CODE));
        when(localMarketDataReader.getAllCurrentPrices()).thenReturn(Map.of(STOCK_CODE, price));
        when(localMarketDataReader.getHoga(STOCK_CODE)).thenReturn(Optional.empty());

        mockMarketDataGenerator.pollAndBroadcast();
        mockMarketDataGenerator.pollAndBroadcast();

        verify(listener, times(1)).onTickReceived(any());
    }

    @Test
    @DisplayName("구독 중인 종목이 market_data.json에 없으면(price==null) 건너뛴다")
    void pollAndBroadcast_PriceMissing_Skips() {
        when(stockSubscriptionManager.getActiveSubscribedStockCodes()).thenReturn(Set.of(STOCK_CODE));
        when(localMarketDataReader.getAllCurrentPrices()).thenReturn(Map.of());

        mockMarketDataGenerator.pollAndBroadcast();

        verify(listener, never()).onTickReceived(any());
        verify(localMarketDataReader, never()).getHoga(any());
    }

    @Test
    @DisplayName("호가 데이터가 없으면 tick만 브로드캐스트하고 onHogaReceived는 호출하지 않는다")
    void pollAndBroadcast_HogaMissing_BroadcastsTickOnly() {
        LocalDateTime updatedAt = LocalDateTime.of(2026, 9, 21, 12, 0, 0);
        CurrentPriceDetailDto price = CurrentPriceDetailDto.builder()
                .stockCode(STOCK_CODE)
                .currentPrice(78500L)
                .changeAmount(0L)
                .changeRate(0.0)
                .updatedAt(updatedAt)
                .build();

        when(stockSubscriptionManager.getActiveSubscribedStockCodes()).thenReturn(Set.of(STOCK_CODE));
        when(localMarketDataReader.getAllCurrentPrices()).thenReturn(Map.of(STOCK_CODE, price));
        when(localMarketDataReader.getHoga(STOCK_CODE)).thenReturn(Optional.empty());

        mockMarketDataGenerator.pollAndBroadcast();

        verify(listener, times(1)).onTickReceived(any());
        verify(listener, never()).onHogaReceived(any());
    }
}
