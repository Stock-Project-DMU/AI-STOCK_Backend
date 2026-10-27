package com.teamfp.aistock.infra.marketdata;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

import org.springframework.stereotype.Component;

import com.teamfp.aistock.infra.marketdata.dto.CurrentPriceDetailDto;
import com.teamfp.aistock.infra.marketdata.dto.MarketCapPeersDto;
import com.teamfp.aistock.infra.marketdata.dto.RankingItemDto;

import lombok.RequiredArgsConstructor;

/**
 * 종목 순위 — 상승률·하락률·시가총액·거래량·거래대금(홈 주요 종목, 시뮬레이션 리밸런싱 후보, AI 상담)과 거래량 급증·시간외
 * 순위(AI 상담). 앞의 5종은 market_data.json 전체 종목을 직접 정렬하고, 뒤의 3종은 local-market-data-generator가
 * {@code _market.surgingVolume}·{@code afterHoursChange}·{@code afterHoursVolume}에 30초마다 담는 모의 순위를 읽는다.
 * 순위는 시장 전체가 아니라 stocks.json에 등록된 종목(105개) 범위 안의 순위다.
 */
@Component
@RequiredArgsConstructor
public class HighItemApiClient {

    private static final int MAX_RANKING_ITEMS = 10;
    private static final int MAX_PEER_ITEMS = 10;

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

    /**
     * 종목 리서치 "시가총액 상위 비교" — 보고 있는 종목과 같은 시장의 시가총액 상위 {@value #MAX_PEER_ITEMS}개와 그 종목의
     * 순위. 이전에는 종목과 상관없이 전체 시가총액 상위 10개({@link #getTopMarketCap()})를 그대로 보여줘 어떤 종목을 보든
     * 같은 목록이었다. 종목이 상위 밖이면 비교할 수 있게 그 종목을 목록 끝에 덧붙인다.
     */
    public MarketCapPeersDto getMarketCapPeers(String stockCode) {
        Map<String, CurrentPriceDetailDto> all = localMarketDataReader.getAllCurrentPrices();
        CurrentPriceDetailDto target = all.get(stockCode);
        if (target == null) {
            return new MarketCapPeersDto(stockCode, null, null, null, 0, List.of());
        }
        String market = target.getMarket();
        // ETF는 상장주식수가 없어 시가총액을 계산할 수 없다(0으로 맨 뒤에 섞임) — 비교 대상에서 빼고, ETF 자체를 보면 순위 없이 돌려준다.
        if (target.isEtf()) {
            return new MarketCapPeersDto(stockCode, target.getStockName(), market, null, 0, List.of());
        }
        List<CurrentPriceDetailDto> sameMarket = all.values().stream()
                .filter(dto -> !dto.isEtf())
                .filter(dto -> market == null || market.equals(dto.getMarket()))
                .sorted(Comparator.comparingLong(this::marketCap).reversed())
                .toList();
        long totalMarketCap = sameMarket.stream().mapToLong(this::marketCap).sum();

        List<RankingItemDto> items = new ArrayList<>();
        Integer targetRank = null;
        for (int i = 0; i < sameMarket.size(); i++) {
            CurrentPriceDetailDto dto = sameMarket.get(i);
            boolean isTarget = stockCode.equals(dto.getStockCode());
            if (isTarget) {
                targetRank = i + 1;
            }
            if (i < MAX_PEER_ITEMS || isTarget) {
                items.add(rankingItem(dto, i + 1, marketCapShareInfo(marketCap(dto), totalMarketCap)).toBuilder()
                        .marketCap(marketCap(dto))
                        .per(valuation(dto.getPer()))
                        .pbr(valuation(dto.getPbr()))
                        .build());
            }
        }
        return new MarketCapPeersDto(stockCode, target.getStockName(), market, targetRank, sameMarket.size(), items);
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
            items.add(rankingItem(dto, rank++, extraInfoFn != null ? extraInfoFn.apply(dto) : null));
        }
        return items;
    }

    /**
     * PER·PBR이 0이면 "계산하지 않음"(우선주 등 — 시세 데이터에 0으로 들어 있다)이라 null로 바꾼다. 적자 기업의 음수 PER은
     * 실제 값이라 그대로 둔다.
     */
    public static Double valuation(Double value) {
        return value == null || value == 0.0 ? null : value;
    }

    private static RankingItemDto rankingItem(CurrentPriceDetailDto dto, int rank, String extraInfo) {
        return RankingItemDto.builder()
                .rank(rank)
                .stockCode(dto.getStockCode())
                .stockName(dto.getStockName())
                .price(dto.getCurrentPrice())
                .changeAmount(dto.getChangeAmount())
                .changeRate(dto.getChangeRate())
                .volume(dto.getVolume())
                .extraInfo(extraInfo)
                .build();
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
