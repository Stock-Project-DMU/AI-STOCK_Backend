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

    // 정규장 판단(실시세 전환 2단계) — 기본은 전송 가능 시간으로 둔다.
    @Mock
    private MarketSessionService marketSessionService;

    private MockMarketDataGenerator mockMarketDataGenerator;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(marketSessionService.isBroadcastWindow()).thenReturn(true);
        mockMarketDataGenerator = new MockMarketDataGenerator(localMarketDataReader, marketSessionService,
                stockSubscriptionManager, List.of(listener));
    }

    @Test
    @DisplayName("정규장이 아니면(장외) 구독 종목이 있어도 파일을 읽지 않고 아무것도 보내지 않는다")
    void pollAndBroadcast_OutsideMarketHours_DoesNothing() {
        when(marketSessionService.isBroadcastWindow()).thenReturn(false);

        mockMarketDataGenerator.pollAndBroadcast();

        verify(stockSubscriptionManager, never()).getActiveSubscribedStockCodes();
        verify(localMarketDataReader, never()).getAllCurrentPrices();
        verify(listener, never()).onTickReceived(any());
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

    // ---------- updatedAt 순서 판단(hasChanged) ----------

    private static CurrentPriceDetailDto priceAt(long currentPrice, LocalDateTime updatedAt) {
        return CurrentPriceDetailDto.builder()
                .stockCode(STOCK_CODE).stockName("삼성전자").currentPrice(currentPrice).changeAmount(-9000L)
                .changeRate(-3.44).volume(9100000L).updatedAt(updatedAt).build();
    }

    @Test
    @DisplayName("이미 보낸 체결보다 이른 updatedAt은 보내지 않는다 — 가격이 뒤로 가지 않게")
    void pollAndBroadcast_OlderUpdatedAt_Skips() {
        when(stockSubscriptionManager.getActiveSubscribedStockCodes()).thenReturn(Set.of(STOCK_CODE));
        when(localMarketDataReader.getHoga(STOCK_CODE)).thenReturn(Optional.empty());
        when(localMarketDataReader.getAllCurrentPrices()).thenReturn(
                Map.of(STOCK_CODE, priceAt(253000L, LocalDateTime.of(2026, 10, 12, 13, 20, 0))),
                Map.of(STOCK_CODE, priceAt(252500L, LocalDateTime.of(2026, 10, 12, 13, 19, 59))));

        mockMarketDataGenerator.pollAndBroadcast();
        mockMarketDataGenerator.pollAndBroadcast();

        ArgumentCaptor<TickData> tickCaptor = ArgumentCaptor.forClass(TickData.class);
        verify(listener, times(1)).onTickReceived(tickCaptor.capture());
        assertThat(tickCaptor.getValue().getCurrentPrice()).isEqualTo(253000L);
    }

    @Test
    @DisplayName("현재가 0 이하 tick은 보내지 않는다 — 0원 tick이 매수 지정가를 모두 체결시키지 않게(2026-10-12 코드리뷰)")
    void pollAndBroadcast_ZeroPrice_Skipped() {
        when(stockSubscriptionManager.getActiveSubscribedStockCodes()).thenReturn(Set.of(STOCK_CODE));
        when(localMarketDataReader.getAllCurrentPrices()).thenReturn(
                Map.of(STOCK_CODE, priceAt(0L, LocalDateTime.of(2026, 10, 12, 13, 20, 0))));

        mockMarketDataGenerator.pollAndBroadcast();

        verify(listener, never()).onTickReceived(any());
    }

    @Test
    @DisplayName("updatedAt이 30초 넘게 뒤로 가면(생성기 재시작·시계 변경) 다시 받는다 — 시세가 멈추지 않게(2026-10-12 마지막 리뷰)")
    void pollAndBroadcast_ClockReset_AcceptsAgain() {
        when(stockSubscriptionManager.getActiveSubscribedStockCodes()).thenReturn(Set.of(STOCK_CODE));
        when(localMarketDataReader.getHoga(STOCK_CODE)).thenReturn(Optional.empty());
        when(localMarketDataReader.getAllCurrentPrices()).thenReturn(
                Map.of(STOCK_CODE, priceAt(253000L, LocalDateTime.of(2026, 10, 12, 13, 20, 0))),
                Map.of(STOCK_CODE, priceAt(252900L, LocalDateTime.of(2026, 10, 12, 13, 19, 59))),
                Map.of(STOCK_CODE, priceAt(251000L, LocalDateTime.of(2026, 10, 12, 13, 10, 0))));

        mockMarketDataGenerator.pollAndBroadcast();
        mockMarketDataGenerator.pollAndBroadcast();
        mockMarketDataGenerator.pollAndBroadcast();

        ArgumentCaptor<TickData> tickCaptor = ArgumentCaptor.forClass(TickData.class);
        verify(listener, times(2)).onTickReceived(tickCaptor.capture());
        // 1초 뒤로 간 값은 버리고(지난 체결), 10분 뒤로 간 값은 재시작으로 보고 받는다
        assertThat(tickCaptor.getAllValues()).extracting(TickData::getCurrentPrice).containsExactly(253000L, 251000L);
    }
}
