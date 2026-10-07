package com.teamfp.aistock.infra.marketdata;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.teamfp.aistock.infra.marketdata.dto.CurrentPriceDetailDto;
import com.teamfp.aistock.infra.marketdata.dto.ExpectedIndexDto;
import com.teamfp.aistock.infra.marketdata.dto.IndustryPriceDto;
import com.teamfp.aistock.infra.marketdata.dto.IndustryTrendDto;

import lombok.RequiredArgsConstructor;

/**
 * 업종 지수(코스피/코스닥) — 현재가, 기간별 추이, 동시호가 예상지수. 지수 현재가는 market_data.json 종목들의 평균 등락률로,
 * 추이·예상지수는 local-market-data-generator가 {@code _market.industryTrend}·{@code _market.expectedIndex}에 담는 값으로
 * 응답한다(fix/local-market-data-stable — 이전 real 모드에서는 외부 시세 데이터 t1511/t1514/t1485를 호출했다).
 *
 * <p><b>실제 지수 값이 아니다</b> — 실제 지수는 상장 전종목 시가총액 가중평균이라 105개 종목으로는 재현할 수 없어, 대략적인
 * 최근 수준({@link #MOCK_BASE_INDEX_VALUE})을 기준으로 같은 시장 종목의 평균 등락률만큼 움직인 근사치다. 생성기의 추이도
 * 같은 기준값을 써서 현재가와 이어진다.</p>
 */
@Component
@RequiredArgsConstructor
public class IndustryApiClient {

    private static final Map<String, String> INDUSTRY_CODE_BY_NAME = Map.of("코스피", "001", "코스닥", "301");
    private static final int MAX_TREND_ITEMS = 5;
    private static final int MAX_PERIOD_MONTHS = 24;
    private static final Map<String, Double> MOCK_BASE_INDEX_VALUE = Map.of("코스피", 3200.0, "코스닥", 780.0);
    private static final Map<String, String> MOCK_MARKET_BY_NAME = Map.of("코스피", "KOSPI", "코스닥", "KOSDAQ");

    private final LocalMarketDataReader localMarketDataReader;

    /** 업종지수 현재가 스냅샷. marketName은 "코스피" 또는 "코스닥" — 그 외 이름이나 해당 시장 종목이 없으면 빈 값. */
    public Optional<IndustryPriceDto> getCurrentPrice(String marketName) {
        String upcode = INDUSTRY_CODE_BY_NAME.getOrDefault(marketName, "001");
        String market = MOCK_MARKET_BY_NAME.get(marketName);
        Double baseIndexValue = MOCK_BASE_INDEX_VALUE.get(marketName);
        if (market == null || baseIndexValue == null) {
            return Optional.empty();
        }
        List<CurrentPriceDetailDto> matched = localMarketDataReader.getAllCurrentPrices().values().stream()
                .filter(dto -> market.equals(dto.getMarket()))
                .toList();
        if (matched.isEmpty()) {
            return Optional.empty();
        }
        double avgChangeRate = matched.stream().mapToDouble(CurrentPriceDetailDto::getChangeRate).average().orElse(0.0);
        double indexValue = baseIndexValue * (1 + avgChangeRate / 100.0);
        return Optional.of(IndustryPriceDto.builder()
                .industryCode(upcode)
                .industryName(marketName)
                .indexValue(Math.round(indexValue * 100.0) / 100.0)
                .changeRate(Math.round(avgChangeRate * 100.0) / 100.0)
                .build());
    }

    /** 최근 {@value #MAX_TREND_ITEMS}거래일 지수 추이. */
    public List<IndustryTrendDto> getRecentTrend(String marketName) {
        return getTrend(marketName, null);
    }

    /**
     * periodMonths가 없으면 일봉 최근 {@value #MAX_TREND_ITEMS}개, 있으면 월봉으로 바꿔 최대 {@value #MAX_PERIOD_MONTHS}개월을
     * 돌려준다(이전 t1514 일/월봉 전환 규칙과 같다).
     */
    public List<IndustryTrendDto> getTrend(String marketName, Integer periodMonths) {
        boolean longPeriod = MarketDataPeriod.isLongPeriod(periodMonths);
        JsonNode trend = localMarketDataReader.getMarketField("industryTrend").path(marketName).path(longPeriod ? "month" : "day");
        List<IndustryTrendDto> items = localMarketDataReader.convertList(trend, IndustryTrendDto.class);
        int count = longPeriod ? Math.min(periodMonths, MAX_PERIOD_MONTHS) : MAX_TREND_ITEMS;
        return items.size() > count ? items.subList(0, count) : items;
    }

    /** 동시호가 예상지수. callAuctionSession은 "장전" 또는 "장후"(그 외는 장전으로 본다). */
    public Optional<ExpectedIndexDto> getExpectedIndex(String marketName, String callAuctionSession) {
        String session = "장후".equals(callAuctionSession) ? "장후" : "장전";
        return localMarketDataReader.convert(
                localMarketDataReader.getMarketField("expectedIndex").path(marketName).path(session), ExpectedIndexDto.class);
    }
}
