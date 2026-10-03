package com.teamfp.aistock.infra.marketdata;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.teamfp.aistock.infra.marketdata.dto.CurrentPriceDetailDto;
import com.teamfp.aistock.infra.marketdata.dto.MultiStockPriceDto;
import com.teamfp.aistock.infra.marketdata.dto.RankingItemDto;
import com.teamfp.aistock.infra.marketdata.dto.RegisteredStockDto;

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

    /**
     * 순위 5종의 {@code (int limit)} 오버로드에 넘기면 "등록 종목 전체"를 뜻한다 — 홈 주요 종목 무한 스크롤과
     * 시뮬레이션 리밸런싱 후보({@code MarketQueryService.getRankings(sort, true)})가 쓴다.
     */
    public static final int ALL_REGISTERED_STOCKS = Integer.MAX_VALUE;

    private final MarketDataAccessTokenProvider accessTokenProvider;
    // market-data.mode=mock일 때만 존재. getTopPriceChangeRate/getTopPriceDeclineRate/getTopMarketCap/
    // getTopVolume/getTopTradingValue 5개(=MarketQueryService.getRankings()가 실제로 노출하는 종류)만
    // mock 분기를 탄다 — 나머지 3개(급증/시간외 2종)는 AI 상담 도구 전용이라 이번 mock 지원
    // 범위 밖이다(순위 mock 지원 추가, 2026-09-21).
    private final Optional<LocalMarketDataReader> localMarketDataReader;
    // real 모드에서 limit이 MAX_RANKING_ITEMS를 넘을 때(=등록 종목 전체 요청) 순위 TR 대신 쓰는 경로 —
    // 등록 종목 목록(RegisteredStockReader)의 종목코드로 t8407 현재가를 받아 직접 정렬한다(2026-10-02).
    private final RegisteredStockReader registeredStockReader;
    private final MarketDataApiClient marketDataApiClient;

    @Value("${market-data.high-item-url}")
    private String highItemUrl;

    public HighItemApiClient(
            MarketDataAccessTokenProvider accessTokenProvider,
            Optional<LocalMarketDataReader> localMarketDataReader,
            RegisteredStockReader registeredStockReader,
            MarketDataApiClient marketDataApiClient,
            @org.springframework.beans.factory.annotation.Qualifier("marketDataRestClientBuilder") RestClient.Builder restClientBuilder) {
        super(restClientBuilder);
        this.accessTokenProvider = accessTokenProvider;
        this.localMarketDataReader = localMarketDataReader;
        this.registeredStockReader = registeredStockReader;
        this.marketDataApiClient = marketDataApiClient;
    }

    /** 등락율상위(t1441) — 코스피+코스닥 전체 시장의 당일 상승률 상위 종목. */
    public List<RankingItemDto> getTopPriceChangeRate() {
        return getTopPriceChangeRate(MAX_RANKING_ITEMS);
    }

    /**
     * limit은 반환 건수 상한이다. 아래 4개 순위 메서드의 {@code (int limit)} 오버로드도 같은 규칙이다.
     * <ul>
     *   <li>mock 모드: 등록 종목(market_data.json) 범위에서 정렬해 상위 limit개.</li>
     *   <li>real 모드, limit ≤ {@value #MAX_RANKING_ITEMS}: 기존 순위 TR(시장 전체 기준) 상위 limit개.</li>
     *   <li>real 모드, limit &gt; {@value #MAX_RANKING_ITEMS}(예: {@link #ALL_REGISTERED_STOCKS}): 순위 TR은 최대
     *       {@value #MAX_RANKING_ITEMS}건만 주므로, 등록 종목 전체의 t8407 현재가를 받아 mock과 같은 기준으로
     *       정렬한다 — 홈 주요 종목 무한 스크롤이 real(배포) 환경에서도 mock과 같은 105개를 받게 하기 위함(2026-10-02).</li>
     * </ul>
     */
    public List<RankingItemDto> getTopPriceChangeRate(int limit) {
        return priceChangeRateRanking(true, limit);
    }

    /** 등락율상위(t1441) — 코스피+코스닥 전체 시장의 당일 하락률 상위 종목. */
    public List<RankingItemDto> getTopPriceDeclineRate() {
        return getTopPriceDeclineRate(MAX_RANKING_ITEMS);
    }

    public List<RankingItemDto> getTopPriceDeclineRate(int limit) {
        return priceChangeRateRanking(false, limit);
    }

    /**
     * t1441 InBlock: gubun1(0:전체/1:코스피/2:코스닥), gubun2(0:상승률/1:하락률/2:보합),
     * gubun3(0:당일/1:전일). 이전에는 "1","2","1"(코스피만·보합·전일)로 호출하고 있어 상승 순위가
     * 아니었다 — 전체 시장(0)·당일(0) 기준으로 고정하고 상승/하락만 gubun2로 나눈다(#13, 2026-09-30).
     * mock 모드·등록 종목 전체 순위도 같은 의미로 맞춰 상승 순위엔 상승 종목만, 하락 순위엔 하락 종목만 담는다.
     */
    private List<RankingItemDto> priceChangeRateRanking(boolean rising, int limit) {
        if (localMarketDataReader.isPresent()) {
            Comparator<CurrentPriceDetailDto> byChangeRate = Comparator.comparingDouble(CurrentPriceDetailDto::getChangeRate);
            return mockRanking(
                    dto -> rising ? dto.getChangeRate() > 0 : dto.getChangeRate() < 0,
                    rising ? byChangeRate.reversed() : byChangeRate, null, limit);
        }
        if (limit > MAX_RANKING_ITEMS) {
            Comparator<MultiStockPriceDto> byChangeRate = Comparator.comparingDouble(dto -> orZero(dto.getChangeRate()));
            return registeredStockRanking(
                    dto -> rising ? orZero(dto.getChangeRate()) > 0 : orZero(dto.getChangeRate()) < 0,
                    rising ? byChangeRate.reversed() : byChangeRate, null, limit);
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
                , limit);
    }

    /** 시가총액상위(t1444) — 코스피+코스닥 전체(upcode="001") 시가총액 상위. */
    public List<RankingItemDto> getTopMarketCap() {
        return getTopMarketCap(MAX_RANKING_ITEMS);
    }

    public List<RankingItemDto> getTopMarketCap(int limit) {
        if (localMarketDataReader.isPresent()) {
            Map<String, CurrentPriceDetailDto> all = localMarketDataReader.get().getAllCurrentPrices();
            long totalMarketCap = all.values().stream().mapToLong(this::marketCap).sum();
            return mockRanking(Comparator.comparingLong(this::marketCap).reversed(),
                    dto -> marketCapShareInfo(marketCap(dto), totalMarketCap),
                    limit);
        }
        if (limit > MAX_RANKING_ITEMS) {
            // t8407에는 시가총액·상장주식수가 없어 등록 종목 목록의 상장주식수 스냅샷 × 실시간 현재가로 계산한다.
            Map<String, Long> listingSharesByCode = registeredStockReader.getRegisteredStocks().stream()
                    .filter(stock -> stock.listingShares() != null)
                    .collect(java.util.stream.Collectors.toMap(
                            RegisteredStockDto::stockCode, RegisteredStockDto::listingShares, (first, second) -> first));
            java.util.function.ToLongFunction<MultiStockPriceDto> marketCapOf =
                    dto -> orZero(dto.getPrice()) * listingSharesByCode.getOrDefault(dto.getStockCode(), 0L) * 1000;
            return registeredStockRanking(dto -> true, Comparator.comparingLong(marketCapOf).reversed(),
                    prices -> {
                        long totalMarketCap = prices.stream().mapToLong(marketCapOf).sum();
                        return dto -> marketCapShareInfo(marketCapOf.applyAsLong(dto), totalMarketCap);
                    }, limit);
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
                , limit);
    }

    /** 거래량상위(t1452) — 오늘(jnilgubun="1") 누적 거래량 상위. */
    public List<RankingItemDto> getTopVolume() {
        return getTopVolume(MAX_RANKING_ITEMS);
    }

    public List<RankingItemDto> getTopVolume(int limit) {
        if (localMarketDataReader.isPresent()) {
            return mockRanking(Comparator.comparingLong(CurrentPriceDetailDto::getVolume).reversed(), null, limit);
        }
        if (limit > MAX_RANKING_ITEMS) {
            return registeredStockRanking(dto -> true,
                    Comparator.comparingLong((MultiStockPriceDto dto) -> orZero(dto.getVolume())).reversed(), null, limit);
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
                , limit);
    }

    /** 거래대금상위(t1463) — 오늘(jnilgubun="1") 누적 거래대금 상위. */
    public List<RankingItemDto> getTopTradingValue() {
        return getTopTradingValue(MAX_RANKING_ITEMS);
    }

    public List<RankingItemDto> getTopTradingValue(int limit) {
        if (localMarketDataReader.isPresent()) {
            return mockRanking(Comparator.comparingLong(this::tradingValue).reversed(),
                    dto -> "거래대금 약 %d백만원".formatted(tradingValue(dto) / 1_000_000), limit);
        }
        if (limit > MAX_RANKING_ITEMS) {
            // t8407 value는 이미 백만원 단위의 실제 누적 거래대금이다(mock의 현재가×거래량 근사치와 다름).
            return registeredStockRanking(dto -> true,
                    Comparator.comparingLong((MultiStockPriceDto dto) -> orZero(dto.getTradingValue())).reversed(),
                    prices -> dto -> "거래대금 %d백만원".formatted(orZero(dto.getTradingValue())), limit);
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
                , limit);
    }

    /**
     * market-data.mode=mock 전용 순위 산출 — LocalMarketDataReader가 제공하는 종목 전체
     * 스냅샷(getAllCurrentPrices(), local-market-data-generator가 t1102로 수집한 실제 값)을
     * comparator로 정렬해 상위 limit개만 뽑는다. real 모드의 각 TR과
     * 달리 mock 데이터는 시장 전체가 아니라 stocks.json에 등록된 종목(2026-09-21 기준 105개)
     * 범위 안에서만 순위를 매긴다는 한계가 있다 — 로컬 개발용 근사치임을 extraInfoFn이 없는
     * 경우 별도로 표기하지 않는다(순위 mock 지원 추가, 2026-09-21).
     */
    private List<RankingItemDto> mockRanking(
            Comparator<CurrentPriceDetailDto> comparator,
            java.util.function.Function<CurrentPriceDetailDto, String> extraInfoFn,
            int limit) {
        return mockRanking(dto -> true, comparator, extraInfoFn, limit);
    }

    private List<RankingItemDto> mockRanking(
            java.util.function.Predicate<CurrentPriceDetailDto> filter,
            Comparator<CurrentPriceDetailDto> comparator,
            java.util.function.Function<CurrentPriceDetailDto, String> extraInfoFn,
            int limit) {
        Map<String, CurrentPriceDetailDto> all = localMarketDataReader.get().getAllCurrentPrices();
        List<RankingItemDto> items = new java.util.ArrayList<>();
        int rank = 1;
        for (CurrentPriceDetailDto dto : all.values().stream().filter(filter).sorted(comparator).toList()) {
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

    /**
     * real 모드 등록 종목 전체 순위 — 순위 TR은 시장 전체 상위 {@value #MAX_RANKING_ITEMS}건만 주므로,
     * 등록 종목 목록(RegisteredStockReader)의 종목코드 전부를 t8407(50종목씩)로 조회해 mock과 같은
     * 기준으로 정렬한다. mock과 마찬가지로 시장 전체가 아니라 등록 종목 범위 안의 순위다.
     * extraInfoFactory는 조회된 전체 시세를 보고(예: 시가총액 비중의 분모) 종목별 부가 정보 함수를 만든다.
     * 등록 종목 목록을 읽지 못했으면 t8407을 호출하지 않고 빈 목록을 반환한다.
     */
    private List<RankingItemDto> registeredStockRanking(
            java.util.function.Predicate<MultiStockPriceDto> filter,
            Comparator<MultiStockPriceDto> comparator,
            java.util.function.Function<List<MultiStockPriceDto>, java.util.function.Function<MultiStockPriceDto, String>> extraInfoFactory,
            int limit) {
        List<String> stockCodes = registeredStockReader.getRegisteredStocks().stream()
                .map(RegisteredStockDto::stockCode)
                .toList();
        if (stockCodes.isEmpty()) {
            return List.of();
        }
        List<MultiStockPriceDto> prices = marketDataApiClient.getMultiStockPricesInBatches(stockCodes).stream()
                .filter(dto -> dto.getPrice() != null && dto.getPrice() > 0)
                .toList();
        java.util.function.Function<MultiStockPriceDto, String> extraInfoFn =
                extraInfoFactory != null ? extraInfoFactory.apply(prices) : null;
        List<RankingItemDto> items = new java.util.ArrayList<>();
        int rank = 1;
        for (MultiStockPriceDto dto : prices.stream().filter(filter).sorted(comparator).toList()) {
            if (rank > limit) {
                break;
            }
            items.add(RankingItemDto.builder()
                    .rank(rank++)
                    .stockCode(dto.getStockCode())
                    .stockName(dto.getStockName())
                    .price(dto.getPrice())
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

    private static long orZero(Long value) {
        return value != null ? value : 0L;
    }

    private static double orZero(Double value) {
        return value != null ? value : 0.0;
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
                , MAX_RANKING_ITEMS);
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
                , MAX_RANKING_ITEMS);
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
                , MAX_RANKING_ITEMS);
    }

    @SuppressWarnings("unchecked")
    private List<RankingItemDto> callAndParse(
            String trCd, String outBlockKey, Map<String, Object> inBlock,
            java.util.function.Function<Map<String, Object>, RankingItemDto.RankingItemDtoBuilder> rowMapper,
            int limit) {
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
            // 순위 TR 경로는 limit과 무관하게 최대 MAX_RANKING_ITEMS건(그 이상은 registeredStockRanking 경로).
            if (items.size() >= Math.min(limit, MAX_RANKING_ITEMS)) {
                break;
            }
        }
        return items;
    }

}
