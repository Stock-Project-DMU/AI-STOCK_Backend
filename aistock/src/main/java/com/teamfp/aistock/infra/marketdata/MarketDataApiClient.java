package com.teamfp.aistock.infra.marketdata;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.teamfp.aistock.infra.marketdata.dto.CallAuctionPriceDto;
import com.teamfp.aistock.infra.marketdata.dto.CurrentPriceDetailDto;
import com.teamfp.aistock.infra.marketdata.dto.HistoricalPriceDto;
import com.teamfp.aistock.infra.marketdata.dto.MultiStockPriceDto;
import com.teamfp.aistock.infra.marketdata.dto.PivotLevelDto;
import com.teamfp.aistock.infra.marketdata.dto.StockRiskFlagDto;

import lombok.extern.slf4j.Slf4j;

/**
 * 외부 시세 데이터 제공사 Open API로 종목 현재가(+PER/PBR/52주 최고·최저 등 시세 종합 정보)를 그때그때
 * (on-demand) REST로 조회하는 클라이언트.
 *
 * {@link MarketDataWebSocketClient}(실시간 tick 스트림)와는 목적이 다르다 — WebSocket은 subscribe()한
 * 종목만 계속 흘러들어오는 구조라, AI 상담처럼 "아무 회사나 갑자기 물어볼 수 있는" 용도에는
 * 맞지 않는다(2026-08-07 확인 — subscribe() 호출부가 아직 어디에도 연결돼 있지 않아, 사실상
 * 어떤 종목도 실시간 캐시가 채워지지 않는 상태였다). 그래서 AI의 get_current_price 도구는
 * WebSocket 캐시 대신 이 REST 클라이언트로 그때그때 직접 조회한다.
 *
 * {@link MarketDataWebSocketClient}와 달리 {@code market-data.mode} 조건 없이 항상 빈으로 생성된다 — 지속
 * 연결을 유지하는 게 아니라 요청마다 독립적으로 호출/실패하는 구조라, GeminiApiClient/
 * DartApiClient와 동일하게 키가 비어 있어도 그냥 실패 응답으로 처리되면 되기 때문이다(코드
 * 수정 없이 키만 나중에 채워 넣어도 그대로 동작).
 *
 * <p>t1102 응답 필드명은 2026-08-07엔 문서 없이 추정으로 작성했는데(당시 삼성전자 실제가로
 * 검증 완료), 이후 외부 시세 데이터 공식 API 카탈로그 문서를 확보해 필드명 전체(166개)를 대조한 결과
 * hname/price/sign/change/diff/volume 등 이미 쓰던 필드명은 전부 정확했다. 이번엔 그 문서를
 * 근거로 PER/PBR/52주 최고·최저·상장주식수·소진율까지 추가로 파싱한다(2026-08-10).</p>
 *
 * <p>다만 sign 필드는 이름만 알고 있었을 뿐 실제로 읽어 쓰지는 않고 있었다 — change(등락액)가
 * 부호 없는 크기로 오는 걸 그대로 changeAmount에 넣어, 하락 종목도 항상 양수로 저장되는 버그가
 * 있었다(local-market-data-generator 작업 중 실제 외부 시세 데이터 응답으로 실측: sign=5(하락)인데
 * change=9500(양수), diff=-3.53(음수) — 서로 모순). {@link MarketDataApiClientSupport#signedLong} 추가로
 * 수정(2026-09-11) — 같은 버그가 t8407(이 클래스의 {@code getMultiStockPrices})과 다른 5개
 * 클라이언트(EtfApiClient/HighItemApiClient/InvestInfoApiClient/IndustryApiClient/
 * SectorApiClient)에도 있어 전부 같은 방식으로 함께 고쳤다.</p>
 *
 * <p>{@code getCurrentPrice()}만 {@code market-data.mode=mock}에서 {@link LocalMarketDataReader}(로컬
 * 파일)로 전환된다(feature/ls-local-data, 2026-08-30). 이 클래스의 나머지 메서드(t1105/t1305/
 * t8407/t1404+t1405/t1486)와 다른 9개 REST 클라이언트(t1716/t3401 등)는 {@link
 * MarketDataAccessTokenProvider}와 동일하게 market-data.mode와 무관하게 항상 실제 외부 시세 데이터 API를 호출한다 — mock 전환
 * 대상은 이번 범위에서 t1102(현재가) 하나로 한정한다.</p>
 */
@Slf4j
@Component
public class MarketDataApiClient extends MarketDataApiClientSupport {

    private static final String CURRENT_PRICE_TR_CD = "t1102";
    private static final String OUT_BLOCK_KEY = "t1102OutBlock";
    // t8407 한 번에 조회할 수 있는 최대 종목 수(제공사 스펙 nrec 최대 50).
    private static final int MAX_MULTI_STOCK_CODES = 50;

    private final MarketDataAccessTokenProvider accessTokenProvider;
    private final Optional<LocalMarketDataReader> localMarketDataReader;

    @Value("${market-data.quote-url}")
    private String marketDataUrl;

    public MarketDataApiClient(
            MarketDataAccessTokenProvider accessTokenProvider,
            Optional<LocalMarketDataReader> localMarketDataReader,
            @org.springframework.beans.factory.annotation.Qualifier("marketDataRestClientBuilder") RestClient.Builder restClientBuilder) {
        super(restClientBuilder);
        this.accessTokenProvider = accessTokenProvider;
        this.localMarketDataReader = localMarketDataReader;
    }

    /**
     * 종목코드로 현재가(장중이면 실시간 체결가, 장 마감 후라면 외부 시세 데이터가 돌려주는 마지막 체결가)와
     * PER/PBR/52주 최고·최저·상장주식수·소진율을 함께 조회한다. 응답 구조가 예상과 다르거나
     * 현재가가 없으면 빈 값을 반환한다. 토큰 발급·TR 호출 자체가 실패하면(제공사 장애) 빈 값으로
     * 삼키지 않고 CustomException(MARKET_DATA_UNAVAILABLE)을 던진다 — 호출자가 "종목 정보 없음"과
     * "제공사 장애"를 구분해 안내할 수 있게 하기 위함이다(외부 장애와 빈 목록 구분 처리, #05).
     *
     * <p>{@code market-data.mode=mock}이면(={@code localMarketDataReader}가 존재하면) 외부 시세 데이터 API를 호출하지
     * 않고 로컬 파일 조회로 대체한다. 로컬 파일이 없거나 파싱에 실패해도 실제 외부 시세 데이터 API로 폴백하지
     * 않고 그대로 빈 값을 반환한다 — mock 모드에서는 로컬 파일이 유일한 데이터 소스다.</p>
     */
    public Optional<CurrentPriceDetailDto> getCurrentPrice(String stockCode) {
        if (localMarketDataReader.isPresent()) {
            return localMarketDataReader.get().getCurrentPrice(stockCode);
        }
        String token = accessTokenProvider.issueAccessToken();
        Map<String, Object> requestBody = Map.of("t1102InBlock", Map.of("shcode", stockCode));

        Map<String, Object> response = call(marketDataUrl, CURRENT_PRICE_TR_CD, requestBody, token, "외부 시세 데이터 현재가 조회 실패");

        return parseCurrentPrice(stockCode, response);
    }

    @SuppressWarnings("unchecked")
    private Optional<CurrentPriceDetailDto> parseCurrentPrice(String stockCode, Map<String, Object> response) {
        if (response == null) {
            return Optional.empty();
        }
        Object outBlockObj = response.get(OUT_BLOCK_KEY);
        if (!(outBlockObj instanceof Map)) {
            log.warn("외부 시세 데이터 현재가 응답에서 {}을 찾지 못함 - stockCode: {}, 응답: {}", OUT_BLOCK_KEY, stockCode, response);
            return Optional.empty();
        }
        Map<String, Object> outBlock = (Map<String, Object>) outBlockObj;

        Long currentPrice = parseLong(outBlock.get("price"));
        if (currentPrice == null) {
            log.warn("외부 시세 데이터 현재가 응답에서 price를 파싱하지 못함 - stockCode: {}, outBlock: {}", stockCode, outBlock);
            return Optional.empty();
        }
        Long changeAmount = signedLong(outBlock.get("change"), outBlock.get("sign"));
        Long volume = parseLong(outBlock.get("volume"));

        return Optional.of(CurrentPriceDetailDto.builder()
                .stockCode(stockCode)
                .stockName(stringOf(outBlock.get("hname")))
                .currentPrice(currentPrice)
                .changeAmount(changeAmount != null ? changeAmount : 0L)
                .changeRate(parseDoubleOrZero(outBlock.get("diff")))
                .volume(volume != null ? volume : 0L)
                .per(parseNullableDouble(outBlock.get("per")))
                .pbr(parseNullableDouble(outBlock.get("pbrx")))
                .high52w(parseLong(outBlock.get("high52w")))
                .high52wDate(stringOf(outBlock.get("high52wdate")))
                .low52w(parseLong(outBlock.get("low52w")))
                .low52wDate(stringOf(outBlock.get("low52wdate")))
                .listingShares(parseLong(outBlock.get("listing")))
                .foreignExhaustionRate(parseNullableDouble(outBlock.get("exhratio")))
                .updatedAt(LocalDateTime.now())
                .build());
    }

    private static final int MAX_HISTORICAL_ITEMS = 5;
    private static final int MAX_CALL_AUCTION_ITEMS = 5;
    // 2026-08-13 추가 — "최근"이 아니라 특정 기간(6개월/1년 등)을 묻는 질문은 일봉 5개로는
    // 애초에 답할 수 없어(도구 결과가 사실을 못 주니 모델이 지어내는 문제가 실측됨), 긴 기간
    // 요청 시 월봉(dwmcode=3)으로 전환한다. 외부 시세 데이터 API가 지원하는 최대 기간을 2년(뉴스 검색과
    // 동일한 상한, CLAUDE.md 팀 합의)으로 맞춰 월봉 24개까지 허용한다.
    private static final int MAX_PERIOD_MONTHS = 24;
    private static final int DWMCODE_DAY = 1;
    private static final int DWMCODE_WEEK = 2;
    private static final int DWMCODE_MONTH = 3;
    // 종목 상세 차트(getChartPrices) 한 번에 받는 최대 봉 수 — 일봉 60(약 3개월)/주봉 52(약 1년)/월봉 60(5년)을 모두 담는 상한.
    private static final int MAX_CHART_ITEMS = 60;

    /** 관리종목(t1404)+투자경고/매매정지(t1405) 여부를 함께 확인한다(2026-08-11 추가). */
    public List<StockRiskFlagDto> getRiskFlags(String stockCode) {
        List<StockRiskFlagDto> flags = new java.util.ArrayList<>();
        flags.addAll(fetchRiskFlags("t1404", "t1404OutBlock1", stockCode, "관리종목"));
        flags.addAll(fetchRiskFlags("t1405", "t1405OutBlock1", stockCode, "투자경고/매매정지"));
        return flags;
    }

    @SuppressWarnings("unchecked")
    private List<StockRiskFlagDto> fetchRiskFlags(String trCd, String outBlockKey, String stockCode, String flagType) {
        String token = accessTokenProvider.issueAccessToken();
        Map<String, Object> inBlock = Map.of("gubun", "0", "jongchk", "1", "cts_shcode", " ");
        Map<String, Object> requestBody = Map.of(trCd + "InBlock", inBlock);

        Map<String, Object> response = call(marketDataUrl, trCd, requestBody, token, "외부 시세 데이터 위험신호(" + trCd + ") 조회 실패");

        if (response == null || !(response.get(outBlockKey) instanceof List)) {
            return List.of();
        }
        List<Map<String, Object>> outBlock = (List<Map<String, Object>>) response.get(outBlockKey);
        return outBlock.stream()
                .filter(row -> stockCode.equals(stringOf(row.get("shcode"))))
                .map(row -> StockRiskFlagDto.builder()
                        .flagType(flagType)
                        .reasonCode(stringOf(row.get("reason")))
                        .date(stringOf(row.get("date")))
                        .build())
                .toList();
    }

    /** 피봇/디마크(t1105) — 전일 시고저 기준 지지·저항선을 조회한다(2026-08-11 추가). */
    public Optional<PivotLevelDto> getPivotLevels(String stockCode) {
        String token = accessTokenProvider.issueAccessToken();
        Map<String, Object> requestBody = Map.of("t1105InBlock", Map.of("shcode", stockCode, "exchgubun", "K"));

        Map<String, Object> response = call(marketDataUrl, "t1105", requestBody, token, "외부 시세 데이터 피봇/디마크 조회 실패");

        if (response == null || !(response.get("t1105OutBlock") instanceof Map)) {
            return Optional.empty();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> outBlock = (Map<String, Object>) response.get("t1105OutBlock");
        return Optional.of(PivotLevelDto.builder()
                .stockCode(stockCode)
                .pivot(parseLong(outBlock.get("pbot")))
                .resistance1(parseLong(outBlock.get("offer1")))
                .support1(parseLong(outBlock.get("supp1")))
                .resistance2(parseLong(outBlock.get("offer2")))
                .support2(parseLong(outBlock.get("supp2")))
                .build());
    }

    /** 기간별주가(t1305) — 최근 며칠치 시세+시가총액+외국인/개인 순매수(2026-08-11 추가). */
    public List<HistoricalPriceDto> getRecentHistoricalPrices(String stockCode) {
        return getHistoricalPrices(stockCode, null);
    }

    /**
     * 기간별주가(t1305) — periodMonths가 없으면 기존과 동일하게 최근 5거래일(dwmcode=1 일봉)을
     * 반환한다. periodMonths가 있으면(6개월/1년처럼 장기간 저점·고점을 묻는 질문 대응,
     * 2026-08-13 추가) 일봉 대신 월봉(dwmcode=3)으로 전환해 최대 24개월(2년, 뉴스 검색과 동일한
     * 상한)까지 조회한다 — 일봉으로 2년을 요청하면 수백 건이 와서 토큰 낭비가 크고, 외부 시세 데이터가 이미
     * 월봉 모드를 지원하므로 그걸 그대로 쓴다.
     */
    public List<HistoricalPriceDto> getHistoricalPrices(String stockCode, Integer periodMonths) {
        boolean longPeriod = periodMonths != null && periodMonths > 0;
        int dwmcode = longPeriod ? DWMCODE_MONTH : DWMCODE_DAY;
        int cnt = longPeriod ? Math.min(periodMonths, MAX_PERIOD_MONTHS) : MAX_HISTORICAL_ITEMS;
        if (localMarketDataReader.isPresent()) {
            return mockHistoricalPrices(stockCode, cnt, longPeriod);
        }
        String token = accessTokenProvider.issueAccessToken();
        Map<String, Object> inBlock = new java.util.LinkedHashMap<>();
        inBlock.put("shcode", stockCode);
        inBlock.put("dwmcode", dwmcode);
        inBlock.put("date", "");
        inBlock.put("idx", 0);
        inBlock.put("cnt", cnt);
        Map<String, Object> requestBody = Map.of("t1305InBlock", inBlock);

        Map<String, Object> response = call(marketDataUrl, "t1305", requestBody, token, "외부 시세 데이터 기간별주가 조회 실패");

        if (response == null || !(response.get("t1305OutBlock1") instanceof List)) {
            return List.of();
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> outBlock = (List<Map<String, Object>>) response.get("t1305OutBlock1");
        return outBlock.stream()
                .limit(cnt)
                .map(row -> HistoricalPriceDto.builder()
                        .date(stringOf(row.get("date")))
                        .open(parseLong(row.get("open")))
                        .high(parseLong(row.get("high")))
                        .low(parseLong(row.get("low")))
                        .close(parseLong(row.get("close")))
                        .changeRate(parseDoubleOrZero(row.get("diff")))
                        .volume(parseLong(row.get("volume")))
                        .marketCap(parseLong(row.get("marketcap")))
                        .foreignNetBuy(parseLong(row.get("fpvolume")))
                        .individualNetBuy(parseLong(row.get("ppvolume")))
                        .build())
                .toList();
    }

    private static final java.time.format.DateTimeFormatter MOCK_HISTORY_DATE_FORMAT =
            java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * market-data.mode=mock 전용 — market_data.json에는 현재가 스냅샷 1건뿐이라 실제 과거
     * 시세가 전혀 없다. 그래서 종목코드로 시드를 고정한 결정적(deterministic) 합성(fabricated)
     * 시계열을 만든다 — 같은 종목은 요청할 때마다 항상 같은 그래프를 그려 화면 깜빡임처럼
     * 보이지 않게 하고, 가장 최근 구간(i=0)의 종가를 mock 현재가와 일치시켜 종목 상세(현재가)와
     * 차트가 서로 모순되지 않게 한다. 실제 과거 시세가 절대 아니므로 real 모드로 착각하지
     * 않도록 이 메서드 밖으로 새어나가는 로그·주석에 "실제"라는 표현을 쓰지 않는다(차트 mock
     * 지원 추가, 2026-09-21).
     */
    private List<HistoricalPriceDto> mockHistoricalPrices(String stockCode, int cnt, boolean longPeriod) {
        Optional<CurrentPriceDetailDto> currentOpt = localMarketDataReader.get().getCurrentPrice(stockCode);
        if (currentOpt.isEmpty()) {
            return List.of();
        }
        CurrentPriceDetailDto current = currentOpt.get();
        java.util.Random random = new java.util.Random(stockCode.hashCode());
        java.time.LocalDate date = java.time.LocalDate.now();

        List<HistoricalPriceDto> result = new java.util.ArrayList<>();
        long close = current.getCurrentPrice();
        for (int i = 0; i < cnt; i++) {
            double openRatio = 1 + (random.nextDouble() - 0.5) * 0.06;
            long open = Math.max(1, Math.round(close * openRatio));
            long high = Math.max(open, close) + Math.round(Math.max(open, close) * random.nextDouble() * 0.02);
            long low = Math.max(1, Math.min(open, close) - Math.round(Math.min(open, close) * random.nextDouble() * 0.02));
            long volume = Math.max(1, Math.round(current.getVolume() * (0.5 + random.nextDouble())));
            double changeRate = open == 0 ? 0.0 : Math.round((close - open) * 10000.0 / open) / 100.0;
            Long marketCap = current.getListingShares() != null ? close * current.getListingShares() * 1000 : null;
            long foreignNetBuy = Math.round(volume * (random.nextDouble() - 0.5) * 0.4);
            long individualNetBuy = Math.round(volume * (random.nextDouble() - 0.5) * 0.4);

            result.add(HistoricalPriceDto.builder()
                    .date(date.format(MOCK_HISTORY_DATE_FORMAT))
                    .open(open)
                    .high(high)
                    .low(low)
                    .close(close)
                    .changeRate(changeRate)
                    .volume(volume)
                    .marketCap(marketCap)
                    .foreignNetBuy(foreignNetBuy)
                    .individualNetBuy(individualNetBuy)
                    .build());

            // 다음(과거) 구간의 종가 = 이번 구간의 시가로 이어 붙여 계단식 시계열을 만든다.
            close = open;
            date = longPeriod ? date.minusMonths(1) : date.minusDays(1);
        }
        return result;
    }

    /**
     * 기간별주가(t1305) — 종목 상세 차트 전용(2026-10-01 추가). dwmcode(1=일봉/2=주봉/3=월봉)를 그대로 넘겨
     * count건(최대 {@value #MAX_CHART_ITEMS})을 받는다. AI 상담용 {@link #getHistoricalPrices(String, Integer)}는
     * months가 오면 월봉으로 바꾸는 규칙이라 차트 탭(일/주)에 그대로 쓰면 월봉이 와서, 차트는 이 메서드로 분리한다.
     * 실측(2026-10-01, 005930): 일봉 60/주봉 52/월봉 60건 요청 시 요청 건수 그대로 반환, 주봉·월봉 날짜는 그 주·그 달의
     * 마지막 거래일(진행 중인 주·달은 오늘).
     */
    public List<HistoricalPriceDto> getChartPrices(String stockCode, int dwmcode, int count) {
        if (dwmcode != DWMCODE_DAY && dwmcode != DWMCODE_WEEK && dwmcode != DWMCODE_MONTH) {
            throw new IllegalArgumentException("지원하지 않는 dwmcode: " + dwmcode);
        }
        int cnt = Math.max(1, Math.min(count, MAX_CHART_ITEMS));
        if (localMarketDataReader.isPresent()) {
            return mockChartPrices(stockCode, dwmcode, cnt);
        }
        String token = accessTokenProvider.issueAccessToken();
        Map<String, Object> inBlock = new java.util.LinkedHashMap<>();
        inBlock.put("shcode", stockCode);
        inBlock.put("dwmcode", dwmcode);
        inBlock.put("date", "");
        inBlock.put("idx", 0);
        inBlock.put("cnt", cnt);
        Map<String, Object> requestBody = Map.of("t1305InBlock", inBlock);

        Map<String, Object> response = call(marketDataUrl, "t1305", requestBody, token, "외부 시세 데이터 차트 기간별주가 조회 실패");

        if (response == null || !(response.get("t1305OutBlock1") instanceof List)) {
            return List.of();
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> outBlock = (List<Map<String, Object>>) response.get("t1305OutBlock1");
        return outBlock.stream()
                .limit(cnt)
                .map(row -> HistoricalPriceDto.builder()
                        .date(stringOf(row.get("date")))
                        .open(parseLong(row.get("open")))
                        .high(parseLong(row.get("high")))
                        .low(parseLong(row.get("low")))
                        .close(parseLong(row.get("close")))
                        .changeRate(parseDoubleOrZero(row.get("diff")))
                        .volume(parseLong(row.get("volume")))
                        .marketCap(parseLong(row.get("marketcap")))
                        .foreignNetBuy(parseLong(row.get("fpvolume")))
                        .individualNetBuy(parseLong(row.get("ppvolume")))
                        .build())
                .toList();
    }

    /**
     * market-data.mode=mock 전용 차트 합성 시계열 — {@link #mockHistoricalPrices}와 같은 규칙(종목코드 시드 고정,
     * 가장 최근 봉 종가 = mock 현재가, 실제 과거 시세 아님)으로 만들되, 봉 간격만 dwmcode에 맞춘다.
     * 일봉은 평일만(주말이면 직전 금요일부터), 주봉은 1주 간격, 월봉은 1개월 간격으로 과거로 거슬러 올라간다.
     */
    private List<HistoricalPriceDto> mockChartPrices(String stockCode, int dwmcode, int cnt) {
        Optional<CurrentPriceDetailDto> currentOpt = localMarketDataReader.get().getCurrentPrice(stockCode);
        if (currentOpt.isEmpty()) {
            return List.of();
        }
        CurrentPriceDetailDto current = currentOpt.get();
        java.util.Random random = new java.util.Random(stockCode.hashCode());
        java.time.LocalDate date = java.time.LocalDate.now();
        if (dwmcode == DWMCODE_DAY) {
            date = previousWeekdayOrSame(date);
        }

        List<HistoricalPriceDto> result = new java.util.ArrayList<>();
        long close = current.getCurrentPrice();
        for (int i = 0; i < cnt; i++) {
            double openRatio = 1 + (random.nextDouble() - 0.5) * 0.06;
            long open = Math.max(1, Math.round(close * openRatio));
            long high = Math.max(open, close) + Math.round(Math.max(open, close) * random.nextDouble() * 0.02);
            long low = Math.max(1, Math.min(open, close) - Math.round(Math.min(open, close) * random.nextDouble() * 0.02));
            long volume = Math.max(1, Math.round(current.getVolume() * (0.5 + random.nextDouble())));
            double changeRate = open == 0 ? 0.0 : Math.round((close - open) * 10000.0 / open) / 100.0;
            Long marketCap = current.getListingShares() != null ? close * current.getListingShares() * 1000 : null;
            long foreignNetBuy = Math.round(volume * (random.nextDouble() - 0.5) * 0.4);
            long individualNetBuy = Math.round(volume * (random.nextDouble() - 0.5) * 0.4);

            result.add(HistoricalPriceDto.builder()
                    .date(date.format(MOCK_HISTORY_DATE_FORMAT))
                    .open(open)
                    .high(high)
                    .low(low)
                    .close(close)
                    .changeRate(changeRate)
                    .volume(volume)
                    .marketCap(marketCap)
                    .foreignNetBuy(foreignNetBuy)
                    .individualNetBuy(individualNetBuy)
                    .build());

            // 다음(과거) 봉의 종가 = 이번 봉의 시가로 이어 붙여 계단식 시계열을 만든다.
            close = open;
            date = switch (dwmcode) {
                case DWMCODE_DAY -> previousWeekdayOrSame(date.minusDays(1));
                case DWMCODE_WEEK -> date.minusWeeks(1);
                default -> date.minusMonths(1);
            };
        }
        return result;
    }

    /** 주말이면 직전 금요일로, 평일이면 그대로 돌려준다(mock 일봉이 장이 열리는 날만 갖도록). */
    private static java.time.LocalDate previousWeekdayOrSame(java.time.LocalDate date) {
        return switch (date.getDayOfWeek()) {
            case SATURDAY -> date.minusDays(1);
            case SUNDAY -> date.minusDays(2);
            default -> date;
        };
    }

    /** API용주식멀티현재가조회(t8407) — 최대 5종목까지 한번에 현재가 조회(2026-08-11 추가). */
    public List<MultiStockPriceDto> getMultiStockPrices(List<String> stockCodes) {
        if (stockCodes == null || stockCodes.isEmpty()) {
            return List.of();
        }
        List<String> limited = stockCodes.size() > 5 ? stockCodes.subList(0, 5) : stockCodes;
        return requestMultiStockPrices(limited);
    }

    /**
     * t8407을 한 번에 최대 {@value #MAX_MULTI_STOCK_CODES}종목씩 나눠 호출해 전체 종목의 현재가를 합쳐 반환한다.
     * {@link #getMultiStockPrices}(AI 상담 도구용, 최대 5종목)와 달리 등록 종목 전체(2026-10-02 기준 105개 →
     * 3회 호출)를 대상으로 순위를 매기는 {@code HighItemApiClient}의 real 모드 전체 순위 전용이다.
     * 응답에 없는 종목은 결과에서 빠진다(빈 목록은 "정상 응답이지만 데이터 없음"). real 모드 전용 —
     * mock 모드에서는 호출부가 {@code LocalMarketDataReader}를 쓰므로 이 메서드를 부르지 않는다.
     */
    public List<MultiStockPriceDto> getMultiStockPricesInBatches(List<String> stockCodes) {
        if (stockCodes == null || stockCodes.isEmpty()) {
            return List.of();
        }
        List<MultiStockPriceDto> prices = new java.util.ArrayList<>();
        for (int from = 0; from < stockCodes.size(); from += MAX_MULTI_STOCK_CODES) {
            int to = Math.min(from + MAX_MULTI_STOCK_CODES, stockCodes.size());
            prices.addAll(requestMultiStockPrices(stockCodes.subList(from, to)));
        }
        return prices;
    }

    private List<MultiStockPriceDto> requestMultiStockPrices(List<String> stockCodes) {
        String token = accessTokenProvider.issueAccessToken();
        String concatenatedCodes = String.join("", stockCodes);
        Map<String, Object> requestBody = Map.of("t8407InBlock",
                Map.of("nrec", stockCodes.size(), "shcode", concatenatedCodes));

        Map<String, Object> response = call(marketDataUrl, "t8407", requestBody, token, "외부 시세 데이터 멀티종목현재가 조회 실패");

        if (response == null || !(response.get("t8407OutBlock1") instanceof List)) {
            return List.of();
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> outBlock = (List<Map<String, Object>>) response.get("t8407OutBlock1");
        return outBlock.stream()
                .map(row -> MultiStockPriceDto.builder()
                        .stockCode(stringOf(row.get("shcode")))
                        .stockName(stringOf(row.get("hname")))
                        .price(parseLong(row.get("price")))
                        .changeAmount(signedLong(row.get("change"), row.get("sign")))
                        .changeRate(parseDoubleOrZero(row.get("diff")))
                        .volume(parseLong(row.get("volume")))
                        .tradingValue(parseLong(row.get("value")))
                        .build())
                .toList();
    }

    /**
     * 시간별예상체결가(t1486) — 동시호가 시간대 종목별 예상체결가(2026-08-11 추가). 이 시간대가
     * 아니면 호출하기 전에 {@link com.teamfp.aistock.global.util.DateUtil#isCallAuctionTime()}로
     * 게이트를 걸어야 한다(호출부인 AiPlanningService의 책임 — 이 클라이언트 자체는 게이트를 모른다).
     */
    public List<CallAuctionPriceDto> getRecentCallAuctionPrices(String stockCode) {
        String token = accessTokenProvider.issueAccessToken();
        Map<String, Object> inBlock = new java.util.LinkedHashMap<>();
        inBlock.put("shcode", stockCode);
        inBlock.put("cts_time", "");
        inBlock.put("cnt", MAX_CALL_AUCTION_ITEMS);
        inBlock.put("exchgubun", "K");
        Map<String, Object> requestBody = Map.of("t1486InBlock", inBlock);

        Map<String, Object> response = call(marketDataUrl, "t1486", requestBody, token, "외부 시세 데이터 동시호가예상체결가 조회 실패");

        if (response == null || !(response.get("t1486OutBlock1") instanceof List)) {
            return List.of();
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> outBlock = (List<Map<String, Object>>) response.get("t1486OutBlock1");
        return outBlock.stream()
                .limit(MAX_CALL_AUCTION_ITEMS)
                .map(row -> CallAuctionPriceDto.builder()
                        .time(stringOf(row.get("chetime")))
                        .price(parseLong(row.get("price")))
                        .changeRate(parseDoubleOrZero(row.get("diff")))
                        .expectedVolume(parseLong(row.get("cvolume")))
                        .build())
                .toList();
    }

}
