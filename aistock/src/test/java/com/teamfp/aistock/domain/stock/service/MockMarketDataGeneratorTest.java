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

    // ---------- 실시간 스트림(실시세 전환 7단계) ----------

    private static final String STREAM_EVENTS = """
            [{"stockCode":"005930","stockName":"삼성전자","currentPrice":253000,"changeAmount":-9000,"changeRate":-3.44,
              "volume":9100000,"updatedAt":"2026-10-12T13:20:00.100","askPrices":[253500],"askVolumes":[10],
              "bidPrices":[253000],"bidVolumes":[20],"tick":true,"hoga":true},
             {"stockCode":"000660","stockName":"SK하이닉스","currentPrice":500000,"changeAmount":1000,"changeRate":0.2,
              "volume":100,"updatedAt":"2026-10-12T13:20:00.100","askPrices":[500500],"tick":true,"hoga":true}]""";

    // 스트림 묶음 변환은 실제 LocalMarketDataReader.convert()로 확인한다(파일·URL은 읽지 않는다).
    private MockMarketDataGenerator streamGenerator() {
        return new MockMarketDataGenerator(new LocalMarketDataReader(), marketSessionService, stockSubscriptionManager,
                List.of(listener));
    }

    private static com.fasterxml.jackson.databind.JsonNode json(String text) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(text);
    }

    @Test
    @DisplayName("스트림: 구독 종목의 tick·호가만 보내고, 같은 updatedAt의 tick은 다시 보내지 않는다(호가는 바뀔 때마다 보낸다)")
    void broadcastStreamUpdates_SubscribedOnly_DeduplicatesTick() throws Exception {
        when(stockSubscriptionManager.getActiveSubscribedStockCodes()).thenReturn(Set.of(STOCK_CODE));
        MockMarketDataGenerator generator = streamGenerator();

        generator.broadcastStreamUpdates(json(STREAM_EVENTS));
        generator.broadcastStreamUpdates(json(STREAM_EVENTS));

        ArgumentCaptor<TickData> tickCaptor = ArgumentCaptor.forClass(TickData.class);
        verify(listener, times(1)).onTickReceived(tickCaptor.capture());
        assertThat(tickCaptor.getValue().getStockCode()).isEqualTo(STOCK_CODE);
        assertThat(tickCaptor.getValue().getCurrentPrice()).isEqualTo(253000L);
        assertThat(tickCaptor.getValue().getChangeAmount()).isEqualTo(9000L); // TickData는 부호 없는 절대값
        ArgumentCaptor<HogaData> hogaCaptor = ArgumentCaptor.forClass(HogaData.class);
        verify(listener, times(2)).onHogaReceived(hogaCaptor.capture());
        assertThat(hogaCaptor.getValue().getAskPrices()).containsExactly(253500L);
    }

    @Test
    @DisplayName("스트림: updatedAt이 바뀐 체결은 매번 보내고, hoga=false인 묶음은 호가를 보내지 않는다")
    void broadcastStreamUpdates_NewUpdatedAt_BroadcastsEachTick() throws Exception {
        when(stockSubscriptionManager.getActiveSubscribedStockCodes()).thenReturn(Set.of(STOCK_CODE));
        MockMarketDataGenerator generator = streamGenerator();
        generator.broadcastStreamUpdates(json("""
                [{"stockCode":"005930","currentPrice":253000,"changeAmount":0,"changeRate":0,"volume":1,
                  "updatedAt":"2026-10-12T13:20:00.100","tick":true,"hoga":false}]"""));
        generator.broadcastStreamUpdates(json("""
                [{"stockCode":"005930","currentPrice":253500,"changeAmount":0,"changeRate":0,"volume":2,
                  "updatedAt":"2026-10-12T13:20:00.200","tick":true,"hoga":false}]"""));

        verify(listener, times(2)).onTickReceived(any());
        verify(listener, never()).onHogaReceived(any());
    }

    @Test
    @DisplayName("스트림: 폴링이 이미 보낸 체결(같은 updatedAt)은 스트림에서 다시 보내지 않는다")
    void broadcastStreamUpdates_AlreadyPolled_SkipsSameTick() throws Exception {
        LocalDateTime updatedAt = LocalDateTime.of(2026, 10, 12, 13, 20, 0, 100_000_000);
        CurrentPriceDetailDto price = CurrentPriceDetailDto.builder()
                .stockCode(STOCK_CODE).stockName("삼성전자").currentPrice(253000L).changeAmount(-9000L)
                .changeRate(-3.44).volume(9100000L).updatedAt(updatedAt).build();
        when(stockSubscriptionManager.getActiveSubscribedStockCodes()).thenReturn(Set.of(STOCK_CODE));
        when(localMarketDataReader.getAllCurrentPrices()).thenReturn(Map.of(STOCK_CODE, price));
        when(localMarketDataReader.getHoga(STOCK_CODE)).thenReturn(Optional.empty());
        when(localMarketDataReader.convert(any(), org.mockito.ArgumentMatchers.eq(CurrentPriceDetailDto.class)))
                .thenReturn(Optional.of(price));

        mockMarketDataGenerator.pollAndBroadcast();
        mockMarketDataGenerator.broadcastStreamUpdates(json("""
                [{"stockCode":"005930","updatedAt":"2026-10-12T13:20:00.100","tick":true,"hoga":false}]"""));

        verify(listener, times(1)).onTickReceived(any());
    }

    @Test
    @DisplayName("스트림이 더 새로운 체결을 보낸 뒤 폴링이 읽은 지난 체결(더 이른 updatedAt)은 보내지 않는다 — 가격이 뒤로 가지 않게")
    void pollAndBroadcast_OlderThanStreamed_Skips() throws Exception {
        CurrentPriceDetailDto older = CurrentPriceDetailDto.builder()
                .stockCode(STOCK_CODE).stockName("삼성전자").currentPrice(252500L).changeAmount(-9500L)
                .changeRate(-3.6).volume(9000000L).updatedAt(LocalDateTime.of(2026, 10, 12, 13, 19, 59)).build();
        CurrentPriceDetailDto newer = CurrentPriceDetailDto.builder()
                .stockCode(STOCK_CODE).stockName("삼성전자").currentPrice(253000L).changeAmount(-9000L)
                .changeRate(-3.44).volume(9100000L).updatedAt(LocalDateTime.of(2026, 10, 12, 13, 20, 0, 100_000_000)).build();
        when(stockSubscriptionManager.getActiveSubscribedStockCodes()).thenReturn(Set.of(STOCK_CODE));
        when(localMarketDataReader.convert(any(), org.mockito.ArgumentMatchers.eq(CurrentPriceDetailDto.class)))
                .thenReturn(Optional.of(newer));
        when(localMarketDataReader.getAllCurrentPrices()).thenReturn(Map.of(STOCK_CODE, older));

        mockMarketDataGenerator.broadcastStreamUpdates(json("""
                [{"stockCode":"005930","tick":true,"hoga":false}]"""));
        mockMarketDataGenerator.pollAndBroadcast();

        ArgumentCaptor<TickData> tickCaptor = ArgumentCaptor.forClass(TickData.class);
        verify(listener, times(1)).onTickReceived(tickCaptor.capture());
        assertThat(tickCaptor.getValue().getCurrentPrice()).isEqualTo(253000L);
    }

    @Test
    @DisplayName("스트림: 현재가 0 이하 tick은 보내지 않는다 — 0원 tick이 매수 지정가를 모두 체결시키지 않게(2026-10-12 코드리뷰)")
    void broadcastStreamUpdates_ZeroPrice_Skipped() throws Exception {
        when(stockSubscriptionManager.getActiveSubscribedStockCodes()).thenReturn(Set.of(STOCK_CODE));
        MockMarketDataGenerator generator = streamGenerator();

        generator.broadcastStreamUpdates(json("""
                [{"stockCode":"005930","currentPrice":0,"volume":0,"updatedAt":"2026-10-12T13:20:00.100","tick":true,"hoga":false}]"""));

        verify(listener, never()).onTickReceived(any());
    }

    @Test
    @DisplayName("updatedAt이 30초 넘게 뒤로 가면(생성기 재시작·시계 변경) 다시 받는다 — 시세가 멈추지 않게(2026-10-12 마지막 리뷰)")
    void broadcastStreamUpdates_ClockReset_AcceptsAgain() throws Exception {
        when(stockSubscriptionManager.getActiveSubscribedStockCodes()).thenReturn(Set.of(STOCK_CODE));
        MockMarketDataGenerator generator = streamGenerator();

        generator.broadcastStreamUpdates(json("""
                [{"stockCode":"005930","currentPrice":253000,"volume":10,"updatedAt":"2026-10-12T13:20:00.000","tick":true,"hoga":false}]"""));
        generator.broadcastStreamUpdates(json("""
                [{"stockCode":"005930","currentPrice":252900,"volume":9,"updatedAt":"2026-10-12T13:19:59.000","tick":true,"hoga":false}]"""));
        generator.broadcastStreamUpdates(json("""
                [{"stockCode":"005930","currentPrice":251000,"volume":5,"updatedAt":"2026-10-12T13:10:00.000","tick":true,"hoga":false}]"""));

        ArgumentCaptor<TickData> tickCaptor = ArgumentCaptor.forClass(TickData.class);
        verify(listener, times(2)).onTickReceived(tickCaptor.capture());
        // 1초 뒤로 간 값은 버리고(지난 체결), 10분 뒤로 간 값은 재시작으로 보고 받는다
        assertThat(tickCaptor.getAllValues()).extracting(TickData::getCurrentPrice).containsExactly(253000L, 251000L);
    }

    @Test
    @DisplayName("스트림: 장외이거나 묶음이 배열이 아니면 아무것도 보내지 않는다")
    void broadcastStreamUpdates_OutsideWindowOrInvalid_DoesNothing() throws Exception {
        MockMarketDataGenerator generator = streamGenerator();
        when(marketSessionService.isBroadcastWindow()).thenReturn(false);
        generator.broadcastStreamUpdates(json(STREAM_EVENTS));

        // 배열이 아니면 장 상태를 보기 전에 돌아온다
        generator.broadcastStreamUpdates(json("{\"stockCode\":\"005930\"}"));
        generator.broadcastStreamUpdates(null);

        verify(stockSubscriptionManager, never()).getActiveSubscribedStockCodes();
        verify(listener, never()).onTickReceived(any());
        verify(listener, never()).onHogaReceived(any());
    }
}
