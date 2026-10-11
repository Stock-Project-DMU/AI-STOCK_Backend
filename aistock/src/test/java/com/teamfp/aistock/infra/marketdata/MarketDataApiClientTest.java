package com.teamfp.aistock.infra.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import com.teamfp.aistock.infra.marketdata.dto.HistoricalPriceDto;
import com.teamfp.aistock.infra.marketdata.dto.MultiStockPriceDto;

/**
 * MarketDataApiClient — 로컬 시세 데이터(market_data.json·price_history.json)만으로 현재가·차트·멀티종목 현재가·
 * 관리/경고·피봇·동시호가를 응답하는지 확인한다(fix/local-market-data-stable).
 */
class MarketDataApiClientTest {

    private static final String STOCK_CODE = "005930";

    @TempDir
    private Path tempDir;

    private MarketDataApiClient client;

    @BeforeEach
    void setUp() throws IOException {
        Files.writeString(tempDir.resolve("market_data.json"), """
                {"005930": {"stockCode":"005930","stockName":"삼성전자","currentPrice":70000,"changeAmount":-500,
                   "changeRate":-0.71,"volume":2000000,"listingShares":5000,
                   "riskFlags":[{"flagType":"투자경고/매매정지","reasonCode":"MOCK-02","date":"20261001"}],
                   "pivot":{"stockCode":"005930","pivot":70100,"resistance1":71000,"support1":69000,"resistance2":72000,"support2":68000},
                   "callAuction":[{"time":"085950","price":70100,"changeRate":-0.5,"expectedVolume":1000},
                                  {"time":"085940","price":70000,"changeRate":-0.7,"expectedVolume":900}]},
                 "000660": {"stockCode":"000660","stockName":"SK하이닉스","currentPrice":100000,"changeRate":1.0,"volume":1000},
                 "_market": {}}""");
        LocalMarketDataReader reader = new LocalMarketDataReader();
        ReflectionTestUtils.setField(reader, "localDataPath", tempDir.toString());
        client = new MarketDataApiClient(reader);
    }

    private LocalDate dateOf(HistoricalPriceDto dto) {
        return LocalDate.parse(dto.getDate(), DateTimeFormatter.BASIC_ISO_DATE);
    }

    @Test
    @DisplayName("현재가는 로컬 시세 그대로, 없는 종목은 빈 값이다")
    void currentPrice() {
        assertThat(client.getCurrentPrice(STOCK_CODE)).get()
                .satisfies(price -> assertThat(price.getCurrentPrice()).isEqualTo(70000L));
        assertThat(client.getCurrentPrice("999999")).isEmpty();
    }

    @Test
    @DisplayName("관리·경고 여부, 피봇, 동시호가를 종목 부가 데이터에서 읽는다")
    void stockExtras() {
        assertThat(client.getRiskFlags(STOCK_CODE)).singleElement()
                .satisfies(flag -> assertThat(flag.getFlagType()).isEqualTo("투자경고/매매정지"));
        assertThat(client.getRiskFlags("000660")).isEmpty();
        assertThat(client.getPivotLevels(STOCK_CODE)).get()
                .satisfies(pivot -> assertThat(pivot.getResistance1()).isEqualTo(71000L));
        assertThat(client.getRecentCallAuctionPrices(STOCK_CODE)).extracting(dto -> dto.getTime())
                .containsExactly("085950", "085940");
    }

    @Test
    @DisplayName("멀티종목 현재가는 요청 종목만 돌려주고 없는 종목은 빼며, 거래대금은 현재가×거래량(백만원) 근사다")
    void multiStockPrices() {
        List<MultiStockPriceDto> result = client.getMultiStockPrices(List.of(STOCK_CODE, "999999"));

        assertThat(result).singleElement().satisfies(price -> {
            assertThat(price.getPrice()).isEqualTo(70000L);
            assertThat(price.getChangeAmount()).isEqualTo(-500L);
            assertThat(price.getTradingValue()).isEqualTo(140_000L);
        });
        assertThat(client.getMultiStockPrices(List.of())).isEmpty();
    }

    @Test
    @DisplayName("과거 시세 스냅샷이 있으면 최신 종가를 현재가에 맞춰 비율 조정하고 기간 수익률은 그대로 둔다")
    void chart_usesSnapshotRescaledToCurrentPrice() throws IOException {
        // 스냅샷 최신 종가 35,000 → 현재가 70,000이므로 모든 봉이 2배가 된다.
        Files.writeString(tempDir.resolve("price_history.json"), """
                {"stocks":{"005930":{"month":[
                  {"date":"20261006","open":34000,"high":36000,"low":33000,"close":35000,"volume":10},
                  {"date":"20260930","open":30000,"high":31000,"low":29000,"close":30000,"volume":9},
                  {"date":"20260831","open":25000,"high":26000,"low":24000,"close":25000,"volume":8}]}}}""");

        List<HistoricalPriceDto> result = client.getChartPrices(STOCK_CODE, 3, 60);

        assertThat(result).extracting(HistoricalPriceDto::getDate).containsExactly("20261006", "20260930", "20260831");
        assertThat(result).extracting(HistoricalPriceDto::getClose).containsExactly(70000L, 60000L, 50000L);
        assertThat(result.get(0).getHigh()).isEqualTo(72000L);
        assertThat(result.get(1).getChangeRate()).isEqualTo(20.0);
        // 시가총액은 이전 t1305와 같은 백만원 단위: 70,000원 × 5,000천주 = 3,500억 = 350,000백만원
        assertThat(result.get(0).getMarketCap()).isEqualTo(350_000L);
    }

    @Test
    @DisplayName("스냅샷이 없으면 합성 봉 — 일봉은 평일만, 주봉은 7일, 월봉은 1개월 간격이고 최신 종가는 현재가다")
    void chart_syntheticIntervals() {
        List<HistoricalPriceDto> daily = client.getChartPrices(STOCK_CODE, 1, 60);
        assertThat(daily).hasSize(60);
        assertThat(daily.get(0).getClose()).isEqualTo(70000L);
        assertThat(daily).extracting(this::dateOf).allSatisfy(date ->
                assertThat(date.getDayOfWeek()).isNotIn(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY));

        List<HistoricalPriceDto> weekly = client.getChartPrices(STOCK_CODE, 2, 52);
        for (int i = 1; i < weekly.size(); i++) {
            assertThat(ChronoUnit.DAYS.between(dateOf(weekly.get(i)), dateOf(weekly.get(i - 1)))).isEqualTo(7);
        }
        List<HistoricalPriceDto> monthly = client.getChartPrices(STOCK_CODE, 3, 60);
        assertThat(monthly).hasSize(60);
        assertThat(client.getChartPrices(STOCK_CODE, 1, 500)).hasSize(60);
    }

    @Test
    @DisplayName("합성 월봉은 종목마다 추세가 달라 36개월 수익률이 0% 근처로 몰리지 않는다(같은 종목은 항상 같은 그래프)")
    void chart_syntheticBarsHaveStockSpecificTrend() throws IOException {
        StringBuilder json = new StringBuilder("{");
        List<String> codes = List.of("005930", "000660", "035420", "035720", "051910", "005380", "068270", "105560");
        for (String code : codes) {
            json.append("\"%s\":{\"stockCode\":\"%s\",\"currentPrice\":100000,\"volume\":1000},".formatted(code, code));
        }
        Files.writeString(tempDir.resolve("market_data.json"), json.substring(0, json.length() - 1) + "}");
        tempDir.resolve("market_data.json").toFile().setLastModified(System.currentTimeMillis() + 5_000);

        List<Double> threeYearReturns = new ArrayList<>();
        for (String code : codes) {
            List<HistoricalPriceDto> bars = client.getChartPrices(code, 3, 37);
            threeYearReturns.add((double) bars.get(0).getClose() / bars.get(36).getClose() - 1);
        }

        assertThat(threeYearReturns).anySatisfy(rate -> assertThat(rate).isGreaterThan(0.2));
        double max = threeYearReturns.stream().mapToDouble(Double::doubleValue).max().orElseThrow();
        double min = threeYearReturns.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
        assertThat(max - min).isGreaterThan(0.3);
        assertThat(client.getChartPrices("005930", 3, 37)).usingRecursiveComparison()
                .isEqualTo(client.getChartPrices("005930", 3, 37));
    }

    @Test
    @DisplayName("AI 상담 기간별 시세 — 기간이 없으면 일봉 5개, 있으면 월봉으로 최대 24개월")
    void historicalPrices() {
        assertThat(client.getRecentHistoricalPrices(STOCK_CODE)).hasSize(5);
        assertThat(client.getHistoricalPrices(STOCK_CODE, 36)).hasSize(24);
        assertThat(client.getHistoricalPrices("999999", 6)).isEmpty();
    }

    @Test
    @DisplayName("지원하지 않는 dwmcode면 예외를 던진다")
    void invalidDwmcode_throws() {
        assertThatThrownBy(() -> client.getChartPrices(STOCK_CODE, 4, 10)).isInstanceOf(IllegalArgumentException.class);
    }
}
