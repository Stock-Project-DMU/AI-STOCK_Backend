package com.teamfp.aistock.infra.marketdata;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.teamfp.aistock.infra.marketdata.dto.CurrentPriceDetailDto;
import com.teamfp.aistock.infra.marketdata.dto.RankingItemDto;

import lombok.extern.slf4j.Slf4j;

/**
 * 외부 시세 데이터 제공사 Open API [주식] 상위종목 카테고리({@code /stock/high-item})를 조회하는 클라이언트.
 * 등락율상위(t1441)/시가총액상위(t1444)/거래량상위(t1452)/거래대금상위(t1463)/
 * 전일동시간대비거래급증(t1466)/시간외등락율상위(t1481)/시간외거래량상위(t1482) 7개 TR을
 * 다룬다 — 전부 "시장 전체를 조건 하나로 훑어 상위 N개 종목을 뽑는" 같은 성격이라, AI 상담
 * 도구(get_market_ranking)에서는 이 7개를 rankingType 파라미터 하나로 묶어 노출한다
 * (AiPlanningService의 DISCLOSURE_TOOL/CAPITAL_CHANGE_TOOL과 같은 "여러 TR을 종류 파라미터로
 * 묶는" 기존 패턴을 그대로 따름).
 *
 * 시간외등락율상위/시간외거래량상위 2개는 시간외 거래 시간대(15:30~18:00)에만 실제로 의미
 * 있는 데이터라, 호출 전 {@link com.teamfp.aistock.global.util.DateUtil#isAfterHoursTradingTime()}로
 * 게이트를 거는 건 이 클라이언트가 아니라 AiPlanningService(도구 실행 계층)의 책임이다 —
 * 이 클라이언트 자체는 "그 시간대가 아니면 호출하면 안 된다"를 모르고 그냥 조회만 한다.
 *
 * <p>7개 메서드 전부 row의 change(등락액)를 sign 없이 그대로 changeAmount에 넣고 있어, 하락
 * 종목도 항상 양수로 저장되는 버그가 있었다(t1102와 동일 패턴, t1441/t1452 실제 응답으로 실측
 * 확인). {@link MarketDataApiClientSupport#signedLong}으로 전부 수정(2026-09-11).</p>
 */
@Slf4j
@Component
public class HighItemApiClient extends MarketDataApiClientSupport {

    private static final int MAX_RANKING_ITEMS = 10;

    private final MarketDataAccessTokenProvider accessTokenProvider;
    // market-data.mode=mock일 때만 존재. getTopPriceChangeRate/getTopPriceDeclineRate/getTopMarketCap/
    // getTopVolume/getTopTradingValue 5개(=MarketQueryService.getRankings()가 실제로 노출하는 종류)만
    // mock 분기를 탄다 — 나머지 3개(급증/시간외 2종)는 AI 상담 도구 전용이라 이번 mock 지원
    // 범위 밖이다(순위 mock 지원 추가, 2026-09-21).
    private final Optional<LocalMarketDataReader> localMarketDataReader;

    @Value("${market-data.high-item-url}")
    private String highItemUrl;

    public HighItemApiClient(
            MarketDataAccessTokenProvider accessTokenProvider,
            Optional<LocalMarketDataReader> localMarketDataReader,
            @org.springframework.beans.factory.annotation.Qualifier("marketDataRestClientBuilder") RestClient.Builder restClientBuilder) {
        super(restClientBuilder);
        this.accessTokenProvider = accessTokenProvider;
        this.localMarketDataReader = localMarketDataReader;
    }

    /** 등락율상위(t1441) — 코스피+코스닥 전체 시장의 당일 상승률 상위 종목. */
    public List<RankingItemDto> getTopPriceChangeRate() {
        return priceChangeRateRanking(true);
    }

    /** 등락율상위(t1441) — 코스피+코스닥 전체 시장의 당일 하락률 상위 종목. */
    public List<RankingItemDto> getTopPriceDeclineRate() {
        return priceChangeRateRanking(false);
    }

    /**
     * t1441 InBlock: gubun1(0:전체/1:코스피/2:코스닥), gubun2(0:상승률/1:하락률/2:보합),
     * gubun3(0:당일/1:전일). 이전에는 "1","2","1"(코스피만·보합·전일)로 호출하고 있어 상승 순위가
     * 아니었다 — 전체 시장(0)·당일(0) 기준으로 고정하고 상승/하락만 gubun2로 나눈다(#13, 2026-09-30).
     * mock 모드도 같은 의미로 맞춰 상승 순위엔 상승 종목만, 하락 순위엔 하락 종목만 담는다.
     */
    private List<RankingItemDto> priceChangeRateRanking(boolean rising) {
        if (localMarketDataReader.isPresent()) {
            Comparator<CurrentPriceDetailDto> byChangeRate = Comparator.comparingDouble(CurrentPriceDetailDto::getChangeRate);
            return mockRanking(
                    dto -> rising ? dto.getChangeRate() > 0 : dto.getChangeRate() < 0,
                    rising ? byChangeRate.reversed() : byChangeRate, null);
        }
        Map<String, Object> inBlock = Map.of(
                "gubun1", "0", "gubun2", rising ? "0" : "1", "gubun3", "0",
                "jc_num", 0, "sprice", 0, "eprice", 0, "volume", 0, "idx", 0, "jc_num2", 0);
        return callAndParse("t1441", "t1441OutBlock1", inBlock, row -> RankingItemDto.builder()
                .stockCode(stringOf(row.get("shcode")))
                .stockName(stringOf(row.get("hname")))
                .price(parseLong(row.get("price")))
                .changeAmount(signedLong(row.get("change"), row.get("sign")))
                .changeRate(parseDoubleOrZero(row.get("diff")))
                .volume(parseLong(row.get("volume")))
                );
    }

    /** 시가총액상위(t1444) — 코스피+코스닥 전체(upcode="001") 시가총액 상위. */
    public List<RankingItemDto> getTopMarketCap() {
        if (localMarketDataReader.isPresent()) {
            Map<String, CurrentPriceDetailDto> all = localMarketDataReader.get().getAllCurrentPrices();
            long totalMarketCap = all.values().stream().mapToLong(this::marketCap).sum();
            return mockRanking(Comparator.comparingLong(this::marketCap).reversed(),
                    dto -> totalMarketCap <= 0 ? "시가총액 비중 0.00%"
                            : "시가총액 비중 %.2f%%".formatted(marketCap(dto) * 100.0 / totalMarketCap));
        }
        Map<String, Object> inBlock = Map.of("upcode", "001", "idx", 0);
        return callAndParse("t1444", "t1444OutBlock1", inBlock, row -> RankingItemDto.builder()
                .stockCode(stringOf(row.get("shcode")))
                .stockName(stringOf(row.get("hname")))
                .price(parseLong(row.get("price")))
                .changeAmount(signedLong(row.get("change"), row.get("sign")))
                .changeRate(parseDoubleOrZero(row.get("diff")))
                .volume(parseLong(row.get("volume")))
                .extraInfo("시가총액 비중 %s%%".formatted(stringOf(row.get("rate"))))
                );
    }

    /** 거래량상위(t1452) — 오늘(jnilgubun="1") 누적 거래량 상위. */
    public List<RankingItemDto> getTopVolume() {
        if (localMarketDataReader.isPresent()) {
            return mockRanking(Comparator.comparingLong(CurrentPriceDetailDto::getVolume).reversed(), null);
        }
        Map<String, Object> inBlock = Map.of(
                "gubun", "1", "jnilgubun", "1", "sdiff", 0, "ediff", 0,
                "jc_num", 0, "sprice", 0, "eprice", 0, "volume", 0, "idx", 0);
        return callAndParse("t1452", "t1452OutBlock1", inBlock, row -> RankingItemDto.builder()
                .stockCode(stringOf(row.get("shcode")))
                .stockName(stringOf(row.get("hname")))
                .price(parseLong(row.get("price")))
                .changeAmount(signedLong(row.get("change"), row.get("sign")))
                .changeRate(parseDoubleOrZero(row.get("diff")))
                .volume(parseLong(row.get("volume")))
                );
    }

    /** 거래대금상위(t1463) — 오늘(jnilgubun="1") 누적 거래대금 상위. */
    public List<RankingItemDto> getTopTradingValue() {
        if (localMarketDataReader.isPresent()) {
            return mockRanking(Comparator.comparingLong(this::tradingValue).reversed(),
                    dto -> "거래대금 약 %d백만원".formatted(tradingValue(dto) / 1_000_000));
        }
        Map<String, Object> inBlock = Map.of(
                "gubun", "1", "jnilgubun", "1", "jc_num", 0, "sprice", 0, "eprice", 0,
                "volume", 0, "idx", 0, "jc_num2", 0);
        return callAndParse("t1463", "t1463OutBlock1", inBlock, row -> RankingItemDto.builder()
                .stockCode(stringOf(row.get("shcode")))
                .stockName(stringOf(row.get("hname")))
                .price(parseLong(row.get("price")))
                .changeAmount(signedLong(row.get("change"), row.get("sign")))
                .changeRate(parseDoubleOrZero(row.get("diff")))
                .volume(parseLong(row.get("volume")))
                .extraInfo("거래대금 %s백만원".formatted(stringOf(row.get("value"))))
                );
    }

    /**
     * market-data.mode=mock 전용 순위 산출 — LocalMarketDataReader가 제공하는 종목 전체
     * 스냅샷(getAllCurrentPrices(), local-market-data-generator가 t1102로 수집한 실제 값)을
     * comparator로 정렬해 상위 {@value #MAX_RANKING_ITEMS}개만 뽑는다. real 모드의 각 TR과
     * 달리 mock 데이터는 시장 전체가 아니라 stocks.json에 등록된 종목(2026-09-21 기준 105개)
     * 범위 안에서만 순위를 매긴다는 한계가 있다 — 로컬 개발용 근사치임을 extraInfoFn이 없는
     * 경우 별도로 표기하지 않는다(순위 mock 지원 추가, 2026-09-21).
     */
    private List<RankingItemDto> mockRanking(
            Comparator<CurrentPriceDetailDto> comparator,
            java.util.function.Function<CurrentPriceDetailDto, String> extraInfoFn) {
        return mockRanking(dto -> true, comparator, extraInfoFn);
    }

    private List<RankingItemDto> mockRanking(
            java.util.function.Predicate<CurrentPriceDetailDto> filter,
            Comparator<CurrentPriceDetailDto> comparator,
            java.util.function.Function<CurrentPriceDetailDto, String> extraInfoFn) {
        Map<String, CurrentPriceDetailDto> all = localMarketDataReader.get().getAllCurrentPrices();
        List<RankingItemDto> items = new java.util.ArrayList<>();
        int rank = 1;
        for (CurrentPriceDetailDto dto : all.values().stream().filter(filter).sorted(comparator).toList()) {
            if (rank > MAX_RANKING_ITEMS) {
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

    /** 시가총액(원) = 현재가 × 상장주식수(천주 단위라 1000을 곱함). listingShares 없으면 0. */
    private long marketCap(CurrentPriceDetailDto dto) {
        Long listingShares = dto.getListingShares();
        return listingShares != null ? dto.getCurrentPrice() * listingShares * 1000 : 0L;
    }

    /** 거래대금(원) 근사치 = 현재가 × 당일 누적 거래량. */
    private long tradingValue(CurrentPriceDetailDto dto) {
        return dto.getCurrentPrice() * dto.getVolume();
    }

    /** 전일동시간대비거래급증(t1466) — 어제 같은 시각 대비 거래량이 급증한 종목. */
    public List<RankingItemDto> getSurgingVolumeVsYesterday() {
        Map<String, Object> inBlock = Map.of(
                "gubun", "1", "type1", "1", "type2", "1",
                "jc_num", 0, "sprice", 0, "eprice", 0, "volume", 0, "idx", 0, "jc_num2", 0);
        return callAndParse("t1466", "t1466OutBlock1", inBlock, row -> RankingItemDto.builder()
                .stockCode(stringOf(row.get("shcode")))
                .stockName(stringOf(row.get("hname")))
                .price(parseLong(row.get("price")))
                .changeAmount(signedLong(row.get("change"), row.get("sign")))
                .changeRate(parseDoubleOrZero(row.get("diff")))
                .volume(parseLong(row.get("volume")))
                .extraInfo("전일 동시각 대비 거래량 %s%% 증가".formatted(stringOf(row.get("voldiff"))))
                );
    }

    /**
     * 시간외등락율상위(t1481) — 시간외 거래(15:30~18:00) 시간대에만 의미 있음. 호출 전
     * 시간대 게이트는 AiPlanningService에서 처리한다.
     */
    public List<RankingItemDto> getTopAfterHoursPriceChangeRate() {
        Map<String, Object> inBlock = Map.of("gubun1", "1", "gubun2", "1", "jongchk", "1", "volume", "1", "idx", 0);
        return callAndParse("t1481", "t1481OutBlock1", inBlock, row -> RankingItemDto.builder()
                .stockCode(stringOf(row.get("shcode")))
                .stockName(stringOf(row.get("hname")))
                .price(parseLong(row.get("price")))
                .changeAmount(signedLong(row.get("change"), row.get("sign")))
                .changeRate(parseDoubleOrZero(row.get("diff")))
                .volume(parseLong(row.get("volume")))
                );
    }

    /**
     * 시간외거래량상위(t1482) — 시간외 거래(15:30~18:00) 시간대에만 의미 있음. 호출 전
     * 시간대 게이트는 AiPlanningService에서 처리한다.
     */
    public List<RankingItemDto> getTopAfterHoursVolume() {
        Map<String, Object> inBlock = Map.of("gubun", "1", "jongchk", "1", "idx", 0, "sort_gbn", 0);
        return callAndParse("t1482", "t1482OutBlock1", inBlock, row -> RankingItemDto.builder()
                .stockCode(stringOf(row.get("shcode")))
                .stockName(stringOf(row.get("hname")))
                .price(parseLong(row.get("price")))
                .changeAmount(signedLong(row.get("change"), row.get("sign")))
                .changeRate(parseDoubleOrZero(row.get("diff")))
                .volume(parseLong(row.get("volume")))
                );
    }

    @SuppressWarnings("unchecked")
    private List<RankingItemDto> callAndParse(
            String trCd, String outBlockKey, Map<String, Object> inBlock,
            java.util.function.Function<Map<String, Object>, RankingItemDto.RankingItemDtoBuilder> rowMapper) {
        String token = accessTokenProvider.issueAccessToken();
        Map<String, Object> requestBody = Map.of(trCd + "InBlock", inBlock);

        Map<String, Object> response = call(highItemUrl, trCd, requestBody, token, "외부 시세 데이터 상위종목(" + trCd + ") 조회 실패");

        if (response == null) {
            return List.of();
        }
        Object outBlockObj = response.get(outBlockKey);
        if (!(outBlockObj instanceof List)) {
            log.warn("외부 시세 데이터 상위종목 응답에서 {}을 찾지 못함 - trCd: {}, 응답: {}", outBlockKey, trCd, response);
            return List.of();
        }
        List<Map<String, Object>> outBlock = (List<Map<String, Object>>) outBlockObj;

        List<RankingItemDto> items = new java.util.ArrayList<>();
        int rank = 1;
        for (Map<String, Object> row : outBlock) {
            items.add(rowMapper.apply(row).rank(rank++).build());
            if (items.size() >= MAX_RANKING_ITEMS) {
                break;
            }
        }
        return items;
    }

}
