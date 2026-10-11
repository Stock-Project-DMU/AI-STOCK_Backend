package com.teamfp.aistock.infra.marketdata;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

import org.springframework.stereotype.Component;

import com.teamfp.aistock.infra.marketdata.dto.CurrentPriceDetailDto;
import com.teamfp.aistock.infra.marketdata.dto.RankingItemDto;

import lombok.RequiredArgsConstructor;

/**
 * 종목 순위 — 상승률·하락률·시가총액·거래량·거래대금(홈 주요 종목, 시뮬레이션 리밸런싱 후보, AI 상담)과 거래량 급증·시간외
 * 순위(AI 상담). 앞의 5종은 market_data.json 전체 종목을 직접 정렬하고, 뒤의 3종은 local-market-data-generator가
 * {@code _market.surgingVolume}·{@code afterHoursChange}·{@code afterHoursVolume}에 30초마다 담는 모의 순위를 읽는다
 * (fix/local-market-data-stable — 이전 real 모드에서는 외부 시세 데이터 t1441/t1444/t1452/t1463/t1466/t1481/t1482를
 * 호출했다). 순위는 시장 전체가 아니라 stocks.json에 등록된 종목(105개) 범위 안의 순위다.
 */
@Component
@RequiredArgsConstructor
public class HighItemApiClient {

    private static final int MAX_RANKING_ITEMS = 10;

    /**
     * 순위 5종의 {@code (int limit)} 오버로드에 넘기면 "등록 종목 전체"를 뜻한다 — 홈 주요 종목 무한 스크롤과
     * 시뮬레이션 리밸런싱 후보({@code MarketQueryService.getRankings(sort, true)})가 쓴다.
     */
    public static final int ALL_REGISTERED_STOCKS = Integer.MAX_VALUE;

    private final LocalMarketDataReader localMarketDataReader;

    /** 당일 상승률 상위(상승 종목만). */
    public List<RankingItemDto> getTopPriceChangeRate() {
        return getTopPriceChangeRate(MAX_RANKING_ITEMS);
    }

    public List<RankingItemDto> getTopPriceChangeRate(int limit) {
        return priceChangeRateRanking(true, limit);
    }

    /** 당일 하락률 상위(하락 종목만). */
    public List<RankingItemDto> getTopPriceDeclineRate() {
        return getTopPriceDeclineRate(MAX_RANKING_ITEMS);
    }

    public List<RankingItemDto> getTopPriceDeclineRate(int limit) {
        return priceChangeRateRanking(false, limit);
    }

    private List<RankingItemDto> priceChangeRateRanking(boolean rising, int limit) {
        Comparator<CurrentPriceDetailDto> byChangeRate = Comparator.comparingDouble(CurrentPriceDetailDto::getChangeRate);
        return ranking(dto -> rising ? dto.getChangeRate() > 0 : dto.getChangeRate() < 0,
                rising ? byChangeRate.reversed() : byChangeRate, null, limit);
    }

    /** 시가총액 상위 — 부가 정보로 등록 종목 합계 대비 시가총액 비중을 붙인다. */
    public List<RankingItemDto> getTopMarketCap() {
        return getTopMarketCap(MAX_RANKING_ITEMS);
    }

    public List<RankingItemDto> getTopMarketCap(int limit) {
        Map<String, CurrentPriceDetailDto> all = localMarketDataReader.getAllCurrentPrices();
        long totalMarketCap = all.values().stream().mapToLong(this::marketCap).sum();
        return ranking(dto -> true, Comparator.comparingLong(this::marketCap).reversed(),
                dto -> marketCapShareInfo(marketCap(dto), totalMarketCap), limit);
    }

    /** 당일 누적 거래량 상위. */
    public List<RankingItemDto> getTopVolume() {
        return getTopVolume(MAX_RANKING_ITEMS);
    }

    public List<RankingItemDto> getTopVolume(int limit) {
        return ranking(dto -> true, Comparator.comparingLong(CurrentPriceDetailDto::getVolume).reversed(), null, limit);
    }

    /** 당일 누적 거래대금(현재가 × 거래량 근사) 상위. */
    public List<RankingItemDto> getTopTradingValue() {
        return getTopTradingValue(MAX_RANKING_ITEMS);
    }

    public List<RankingItemDto> getTopTradingValue(int limit) {
        return ranking(dto -> true, Comparator.comparingLong(this::tradingValue).reversed(),
                dto -> "거래대금 약 %d백만원".formatted(tradingValue(dto) / 1_000_000), limit);
    }

    /** 전일 동시각 대비 거래량 급증 종목(최대 {@value #MAX_RANKING_ITEMS}건). */
    public List<RankingItemDto> getSurgingVolumeVsYesterday() {
        return marketRanking("surgingVolume");
    }

    /** 시간외 등락률 상위 — 시간외 거래(15:30~18:00) 시간대 게이트는 AiPlanningService가 건다. */
    public List<RankingItemDto> getTopAfterHoursPriceChangeRate() {
        return marketRanking("afterHoursChange");
    }

    /** 시간외 거래량 상위 — 시간대 게이트는 AiPlanningService가 건다. */
    public List<RankingItemDto> getTopAfterHoursVolume() {
        return marketRanking("afterHoursVolume");
    }

    private List<RankingItemDto> marketRanking(String field) {
        List<RankingItemDto> items = localMarketDataReader.getMarketList(field, RankingItemDto.class);
        return items.size() > MAX_RANKING_ITEMS ? items.subList(0, MAX_RANKING_ITEMS) : items;
    }

    private List<RankingItemDto> ranking(Predicate<CurrentPriceDetailDto> filter, Comparator<CurrentPriceDetailDto> comparator,
            Function<CurrentPriceDetailDto, String> extraInfoFn, int limit) {
        List<RankingItemDto> items = new ArrayList<>();
        int rank = 1;
        for (CurrentPriceDetailDto dto : localMarketDataReader.getAllCurrentPrices().values().stream()
                .filter(filter).sorted(comparator).toList()) {
            if (rank > limit) {
                break;
            }
            items.add(RankingItemDto.builder()
                    .rank(rank++)
                    .stockCode(dto.getStockCode())
                    .stockName(dto.getStockName())
                    .price(dto.getCurrentPrice())
                    .changeAmount(dto.getChangeAmount())
                    .changeRate(dto.getChangeRate())
                    .volume(dto.getVolume())
                    .extraInfo(extraInfoFn != null ? extraInfoFn.apply(dto) : null)
                    .build());
        }
        return items;
    }

    private static String marketCapShareInfo(long marketCap, long totalMarketCap) {
        return totalMarketCap <= 0 ? "시가총액 비중 0.00%"
                : "시가총액 비중 %.2f%%".formatted(marketCap * 100.0 / totalMarketCap);
    }

    /** 시가총액(원) = 현재가 × 상장주식수(천주 단위라 1000을 곱함). listingShares 없으면 0. */
    private long marketCap(CurrentPriceDetailDto dto) {
        Long listingShares = dto.getListingShares();
        return listingShares != null ? dto.getCurrentPrice() * listingShares * 1000 : 0L;
    }

    /** 거래대금(원) 근사치 = 현재가 × 당일 누적 거래량. */
    private long tradingValue(CurrentPriceDetailDto dto) {
        return dto.getCurrentPrice() * dto.getVolume();
    }
}
