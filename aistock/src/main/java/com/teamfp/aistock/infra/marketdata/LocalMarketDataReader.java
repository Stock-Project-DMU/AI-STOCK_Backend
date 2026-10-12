package com.teamfp.aistock.infra.marketdata;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.teamfp.aistock.infra.marketdata.dto.CurrentPriceDetailDto;
import com.teamfp.aistock.infra.marketdata.dto.HistoricalPriceDto;
import com.teamfp.aistock.infra.marketdata.dto.HogaData;

import lombok.extern.slf4j.Slf4j;

/**
 * 시세 데이터의 유일한 원천 — local-market-data-generator가 갱신하는 {@code market_data.json}(또는 같은 내용을
 * 내려주는 HTTP 엔드포인트)과 과거 시세 스냅샷 {@code price_history.json}을 읽는다(fix/local-market-data-stable에서
 * 외부 시세 데이터 제공사 API 직접 호출(real 모드)을 없애면서 모든 시세 클라이언트가 이 컴포넌트만 쓰게 됐다).
 *
 * <p>{@code market_data.json} 구조 — 종목코드를 키로 종목 객체를 담고, 시장 전체 데이터는 {@code "_market"} 키에 담는다.
 * {@code "_"}로 시작하는 키는 종목이 아니므로 종목 목록({@link #getAllCurrentPrices()})에서 뺀다.</p>
 * <pre>
 * {"005930": {현재가 필드(CurrentPriceDetailDto) + 호가 필드(HogaData) + 종목 부가 데이터(investorTrend, themes, ...)},
 *  "_market": {hotThemes, investorSummary, overseasIndexes, ...}}
 * </pre>
 * 부가 데이터 필드명은 응답 DTO 필드명과 같아 그대로 역직렬화한다(서로 모르는 필드는 무시 —
 * {@code FAIL_ON_UNKNOWN_PROPERTIES=false}). 원본을 찾지 못하거나 파싱에 실패하거나 키가 없으면 예외 없이 빈 값을 돌려준다.
 *
 * <p><b>원본은 {@code market-data.url} 값으로 갈린다</b> — 비어 있으면(로컬 개발) {@code market-data.local-path}
 * 디렉토리의 파일을 읽고, 값이 있으면(백엔드가 생성기와 파일 시스템을 공유하지 못하는 배포 서버) 그 URL(생성기의
 * {@code GET /market-data})로 같은 JSON을 가져온다. 파일은 수정 시각이 바뀔 때만, URL은 {@value #URL_CACHE_MILLIS}ms가
 * 지났을 때만 다시 읽는다 — 부가 데이터가 붙어 파일이 수백 KB라 조회마다 다시 파싱하지 않기 위해서다(생성기는 1초마다 쓴다).
 * URL은 직전 응답의 ETag를 {@code If-None-Match}로 보내 내용이 그대로면 304만 받고(다운로드·파싱 생략), 바뀌었으면 gzip으로
 * 받는다(실시세 전환 7단계).</p>
 */
@Slf4j
@Component
public class LocalMarketDataReader {

    private static final String MARKET_DATA_FILE_NAME = "market_data.json";
    // 과거 봉 스냅샷 — local-market-data-generator/history_collector.py가 만든다.
    private static final String PRICE_HISTORY_FILE_NAME = "price_history.json";
    /** 시장 전체 부가 데이터가 담긴 키. */
    public static final String MARKET_KEY = "_market";
    private static final long URL_CACHE_MILLIS = 1_000L;

    // updatedAt(LocalDateTime) 역직렬화를 위해 JavaTimeModule을 등록하고, 종목 객체 하나에 여러 DTO의 필드가 섞여
    // 있어 FAIL_ON_UNKNOWN_PROPERTIES를 끈다.
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .setVisibility(PropertyAccessor.FIELD, Visibility.ANY)
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Value("${market-data.local-path:}")
    private String localDataPath;

    // 값이 비어있지 않으면(배포 환경) 파일 대신 이 URL로 GET 요청해 같은 JSON을 가져온다. 테스트가 스프링 없이
    // new로 만들 때 null이 되지 않도록 빈 문자열로 초기화한다.
    @Value("${market-data.url:}")
    private String localDataUrl = "";

    private static final int CONNECT_TIMEOUT_MS = 3_000;
    private static final int READ_TIMEOUT_MS = 5_000;
    private final RestClient restClient = createRestClient();

    // market_data.json 캐시 — 원본(파일 경로 또는 URL), 파일 수정 시각·크기(또는 URL 조회 시각)가 같으면 재사용한다.
    private JsonNode cachedRoot = MissingNode.getInstance();
    private String cachedSource;
    private long cachedVersion = Long.MIN_VALUE;
    private long cachedAtMillis;
    private String cachedEtag;
    private Map<String, CurrentPriceDetailDto> cachedPrices = Map.of();

    private PriceHistoryFile cachedPriceHistory;
    private long cachedPriceHistoryModifiedAt = -1L;
    private long cachedPriceHistoryFetchedAt;
    private String cachedPriceHistoryEtag;
    // 배포 서버(URL 원본)에서 과거 시세를 다시 받는 간격 — 과거 시세는 장 마감 후 하루 한 번 바뀐다.
    private static final long PRICE_HISTORY_URL_CACHE_MILLIS = 600_000L;
    // 원격 과거 시세를 받지 못했을 때 다시 시도하기까지의 간격 — 10분 동안 합성 차트로 남지 않게 한다(2026-10-12 코드리뷰).
    private static final long PRICE_HISTORY_RETRY_MILLIS = 30_000L;
    // 과거 시세(약 2.6MB) 다운로드·파싱은 현재가 조회(readRoot·getAllCurrentPrices)와 다른 잠금을 쓴다 — 받는 동안 실시간 시세·주문 조회가
    // 기다리지 않게 한다(2026-10-12 코드리뷰).
    private final Object priceHistoryLock = new Object();

    private static RestClient createRestClient() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        requestFactory.setReadTimeout(READ_TIMEOUT_MS);
        return RestClient.builder().requestFactory(requestFactory).build();
    }

    /** 종목코드의 현재가. 원본·종목이 없으면 빈 값. */
    public Optional<CurrentPriceDetailDto> getCurrentPrice(String stockCode) {
        CurrentPriceDetailDto price = getAllCurrentPrices().get(stockCode);
        if (price == null) {
            log.warn("시세 데이터에 종목코드 없음 - stockCode: {}", stockCode);
            return Optional.empty();
        }
        return Optional.of(price);
    }

    /**
     * 전체 종목의 현재가(종목코드 → 현재가, {@code "_"}로 시작하는 키 제외). 원본이 바뀌지 않았으면 이전에 변환한 맵을
     * 그대로 돌려준다(MockMarketDataGenerator가 5초마다 부른다).
     */
    public synchronized Map<String, CurrentPriceDetailDto> getAllCurrentPrices() {
        JsonNode root = readRoot();
        if (root != cachedPricesRoot) {
            Map<String, CurrentPriceDetailDto> prices = new LinkedHashMap<>();
            root.properties().forEach(entry -> {
                if (!entry.getKey().startsWith("_") && entry.getValue().isObject()) {
                    convert(entry.getValue(), CurrentPriceDetailDto.class).ifPresent(price -> prices.put(entry.getKey(), price));
                }
            });
            cachedFilePrices = Map.copyOf(prices);
            cachedPricesRoot = root;
            mergedLiveQuotesVersion = -1L;
        }
        long liveVersion = liveQuotesVersion.get();
        if (liveVersion != mergedLiveQuotesVersion) {
            cachedPrices = mergeLiveQuotes(cachedFilePrices);
            mergedLiveQuotesVersion = liveVersion;
        }
        return cachedPrices;
    }

    private JsonNode cachedPricesRoot;
    private Map<String, CurrentPriceDetailDto> cachedFilePrices = Map.of();

    // 실시간 스트림(MarketDataStreamClient, 실시세 전환 7단계)으로 받은 종목별 최신 체결. 파일(1초 저장·1초 캐시)보다 새로우면
    // 현재가 조회에 덮어 써서 REST 현재가·주문 체결가도 화면 실시간 시세와 같은 값을 쓴다. 스트림이 끊기면 파일 값이 더 새로워져
    // 자연히 파일 값으로 돌아간다(updatedAt 비교).
    private final ConcurrentHashMap<String, CurrentPriceDetailDto> liveQuotes = new ConcurrentHashMap<>();
    // updatedAt이 이만큼 넘게 뒤로 가면 생성기 재시작·시계 변경으로 보고 다시 받는다 — 화면 전송(MockMarketDataGenerator)과 같은 기준이라
    // 화면 가격과 REST·주문 체결가가 어긋나지 않는다(2026-10-12 코드리뷰).
    static final long CLOCK_RESET_TOLERANCE_SECONDS = 30;
    private final AtomicLong liveQuotesVersion = new AtomicLong();
    private long mergedLiveQuotesVersion = -1L;

    /** 실시간 스트림 묶음(JSON 배열) 중 체결(tick=true)을 종목별 최신 값으로 기억한다. 더 이른 updatedAt은 버린다. */
    public void applyStreamQuotes(JsonNode events) {
        if (events == null || !events.isArray()) {
            return;
        }
        boolean isChanged = false;
        for (JsonNode event : events) {
            if (!event.path("tick").asBoolean(false)) {
                continue;
            }
            CurrentPriceDetailDto quote = convert(event, CurrentPriceDetailDto.class).orElse(null);
            if (quote == null || quote.getStockCode() == null || quote.getUpdatedAt() == null || quote.getCurrentPrice() <= 0) {
                continue;
            }
            CurrentPriceDetailDto previous = liveQuotes.get(quote.getStockCode());
            if (previous == null || quote.getUpdatedAt().isAfter(previous.getUpdatedAt())
                    || quote.getUpdatedAt().isBefore(previous.getUpdatedAt().minusSeconds(CLOCK_RESET_TOLERANCE_SECONDS))) {
                liveQuotes.put(quote.getStockCode(), quote);
                isChanged = true;
            }
        }
        if (isChanged) {
            liveQuotesVersion.incrementAndGet();
        }
    }

    private Map<String, CurrentPriceDetailDto> mergeLiveQuotes(Map<String, CurrentPriceDetailDto> filePrices) {
        if (liveQuotes.isEmpty()) {
            return filePrices;
        }
        Map<String, CurrentPriceDetailDto> merged = new LinkedHashMap<>(filePrices);
        liveQuotes.forEach((stockCode, live) -> {
            CurrentPriceDetailDto base = merged.get(stockCode);
            if (base != null && (base.getUpdatedAt() == null || live.getUpdatedAt().isAfter(base.getUpdatedAt()))) {
                merged.put(stockCode, base.toBuilder()
                        .currentPrice(live.getCurrentPrice())
                        .changeAmount(live.getChangeAmount())
                        .changeRate(live.getChangeRate())
                        .volume(live.getVolume())
                        .openPrice(live.getOpenPrice() != null ? live.getOpenPrice() : base.getOpenPrice())
                        .highPrice(live.getHighPrice() != null ? live.getHighPrice() : base.getHighPrice())
                        .lowPrice(live.getLowPrice() != null ? live.getLowPrice() : base.getLowPrice())
                        .tradingDate(live.getTradingDate() != null ? live.getTradingDate() : base.getTradingDate())
                        .updatedAt(live.getUpdatedAt())
                        .build());
            }
        });
        return Map.copyOf(merged);
    }

    /** 종목코드의 호가(askPrices 등). 원본·종목·호가 필드가 없으면 빈 값. */
    public Optional<HogaData> getHoga(String stockCode) {
        Optional<HogaData> hoga = convert(stockNode(stockCode), HogaData.class);
        if (hoga.isEmpty() || hoga.get().getAskPrices() == null) {
            log.warn("시세 데이터에 종목코드의 호가 데이터 없음 - stockCode: {}", stockCode);
            return Optional.empty();
        }
        return hoga;
    }

    /** 종목 객체의 부가 데이터 목록(예: investorTrend, themes, opinions). 없으면 빈 목록. */
    public <T> List<T> getStockList(String stockCode, String field, Class<T> elementType) {
        return convertList(stockNode(stockCode).path(field), elementType);
    }

    /** 종목 객체의 부가 데이터 객체(예: pivot). 없으면 빈 값. */
    public <T> Optional<T> getStockObject(String stockCode, String field, Class<T> type) {
        return convert(stockNode(stockCode).path(field), type);
    }

    /** 종목 객체의 부가 데이터 원본 노드(DTO로 바로 옮기지 않는 구조 — 예: credit). 없으면 MissingNode. */
    public JsonNode getStockField(String stockCode, String field) {
        return stockNode(stockCode).path(field);
    }

    /** 시장 전체 부가 데이터 목록(예: hotThemes, newListings). 없으면 빈 목록. */
    public <T> List<T> getMarketList(String field, Class<T> elementType) {
        return convertList(marketNode().path(field), elementType);
    }

    /** 시장 전체 부가 데이터 객체(예: investorSummary). 없으면 빈 값. */
    public <T> Optional<T> getMarketObject(String field, Class<T> type) {
        return convert(marketNode().path(field), type);
    }

    /** 시장 전체 부가 데이터 원본 노드(맵 구조 — 예: overseasIndexes, industryTrend). 없으면 MissingNode. */
    public JsonNode getMarketField(String field) {
        return marketNode().path(field);
    }

    /** JsonNode를 원하는 DTO로 옮긴다(시장 맵의 값처럼 호출부가 노드를 직접 고른 경우). 실패하면 빈 값. */
    public <T> Optional<T> convert(JsonNode node, Class<T> type) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(OBJECT_MAPPER.treeToValue(node, type));
        } catch (IOException | IllegalArgumentException e) {
            log.warn("시세 데이터 변환 실패 - type: {}, 사유: {}", type.getSimpleName(), e.getMessage());
            return Optional.empty();
        }
    }

    /** JsonNode 배열을 DTO 목록으로 옮긴다. 배열이 아니거나 실패하면 빈 목록. */
    public <T> List<T> convertList(JsonNode node, Class<T> elementType) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        try {
            JavaType listType = OBJECT_MAPPER.getTypeFactory().constructCollectionType(List.class, elementType);
            List<T> result = OBJECT_MAPPER.readerFor(listType).readValue(node);
            return result == null ? List.of() : result;
        } catch (IOException | IllegalArgumentException e) {
            log.warn("시세 데이터 목록 변환 실패 - type: {}, 사유: {}", elementType.getSimpleName(), e.getMessage());
            return List.of();
        }
    }

    /**
     * 종목의 과거 봉(최신순)을 {@code price_history.json}에서 꺼낸다. dwmcode는 1=일봉, 2=주봉, 3=월봉이다. 이 파일은
     * local-market-data-generator의 history_collector.py가 기간별주가(t1305)를 한 번 받아 저장해 두는 스냅샷이다.
     * 파일이 없거나(수집 전) 종목·봉 종류가 없거나 파싱에 실패하면 빈 목록을 돌려주고, 호출부({@link MarketDataApiClient})가
     * 합성 시세로 대체한다. 파일은 수정 시각이 바뀌었을 때만 다시 읽는다. {@code market-data.url}(HTTP 원본, 배포 서버) 모드에서는
     * 생성기의 {@code GET /price-history}에서 같은 JSON을 받아 {@value #PRICE_HISTORY_URL_CACHE_MILLIS}ms 동안 재사용한다 —
     * 이전에는 배포 서버가 이 파일을 읽지 못해 차트가 항상 합성 시세였다(실시세 전환 5단계).
     */
    public List<HistoricalPriceDto> getPriceHistory(String stockCode, int dwmcode) {
        PriceHistoryFile history = localDataUrl.isBlank() ? readPriceHistory() : readPriceHistoryFromUrl();
        StockPriceHistory stockHistory = history.stocks == null ? null : history.stocks.get(stockCode);
        if (stockHistory == null) {
            return List.of();
        }
        List<HistoricalPriceDto> bars = switch (dwmcode) {
            case 1 -> stockHistory.day;
            case 2 -> stockHistory.week;
            case 3 -> stockHistory.month;
            default -> null;
        };
        if (bars == null) {
            return List.of();
        }
        return bars; // 주·월봉 거래량은 파일을 읽을 때 이미 주 단위로 바꿔 두었다(normalizeWeekMonthVolumes)
    }

    // 주·월봉 거래량은 천 주 단위로 수집된다 — 일봉·실시세와 같은 주 단위로 맞춘다(차트 툴팁·오늘 봉 합산이 한 단위). 하루 한 번 바뀌는
    // 파일이라 읽을 때 한 번만 바꿔 두고, 조회마다 다시 계산하지 않는다(2026-10-12 코드리뷰).
    private static PriceHistoryFile normalizeWeekMonthVolumes(PriceHistoryFile history) {
        if (history == null || history.stocks == null) {
            return history;
        }
        for (StockPriceHistory stock : history.stocks.values()) {
            if (stock == null) {
                continue;
            }
            stock.week = toShares(stock.week);
            stock.month = toShares(stock.month);
        }
        return history;
    }

    private static List<HistoricalPriceDto> toShares(List<HistoricalPriceDto> bars) {
        if (bars == null) {
            return null;
        }
        return bars.stream()
                .map(bar -> bar.getVolume() == null ? bar : bar.toBuilder().volume(bar.getVolume() * WEEK_MONTH_VOLUME_UNIT).build())
                .toList();
    }

    // LS t1305 주·월봉 거래량 단위(천 주) — 5종목에서 같은 주 일봉 거래량 합 ÷ 주봉 거래량 = 1,000으로 확인(2026-10-12).
    private static final long WEEK_MONTH_VOLUME_UNIT = 1_000L;

    private JsonNode stockNode(String stockCode) {
        if (stockCode == null || stockCode.startsWith("_")) {
            return MissingNode.getInstance();
        }
        return readRoot().path(stockCode);
    }

    private JsonNode marketNode() {
        return readRoot().path(MARKET_KEY);
    }

    private synchronized JsonNode readRoot() {
        return localDataUrl.isBlank() ? readRootFromFile() : readRootFromUrl();
    }

    private JsonNode readRootFromFile() {
        File file = new File(localDataPath, MARKET_DATA_FILE_NAME);
        if (!file.exists()) {
            log.warn("로컬 시세 파일을 찾지 못함 - path: {}", file.getPath());
            return remember(file.getPath(), Long.MIN_VALUE + 1, MissingNode.getInstance());
        }
        long version = file.lastModified() * 31 + file.length();
        if (file.getPath().equals(cachedSource) && version == cachedVersion) {
            return cachedRoot;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(file);
            return remember(file.getPath(), version, root instanceof ObjectNode ? root : MissingNode.getInstance());
        } catch (IOException e) {
            log.warn("로컬 시세 파일 파싱 실패 - path: {}, 사유: {}", file.getPath(), e.getMessage());
            return remember(file.getPath(), version, MissingNode.getInstance());
        }
    }

    private JsonNode readRootFromUrl() {
        long now = System.currentTimeMillis();
        if (localDataUrl.equals(cachedSource) && now - cachedAtMillis < URL_CACHE_MILLIS) {
            return cachedRoot;
        }
        try {
            RemoteBody remote = fetchRemote(localDataUrl, localDataUrl.equals(cachedSource) ? cachedEtag : null);
            if (remote.isNotModified()) {
                cachedAtMillis = now;
                return cachedRoot;
            }
            if (remote.body().length == 0) {
                log.warn("원격 시세 데이터 응답이 비어있음 - url: {}", localDataUrl);
                cachedEtag = null;
                return remember(localDataUrl, now, MissingNode.getInstance());
            }
            JsonNode root = OBJECT_MAPPER.readTree(remote.body());
            cachedEtag = remote.etag();
            return remember(localDataUrl, now, root instanceof ObjectNode ? root : MissingNode.getInstance());
        } catch (Exception e) {
            log.warn("원격 시세 데이터 조회 실패 - url: {}, 사유: {}", localDataUrl, e.getMessage());
            cachedEtag = null;
            return remember(localDataUrl, now, MissingNode.getInstance());
        }
    }

    /**
     * 생성기 HTTP 서버에서 JSON 본문을 받는다. {@code etag}가 있으면 {@code If-None-Match}로 보내 내용이 그대로면 304
     * ({@link RemoteBody#isNotModified()})를 돌려주고, 본문은 gzip으로 받아 풀어 준다. 그 밖의 상태 코드는 예외.
     */
    private RemoteBody fetchRemote(String url, String etag) {
        return restClient.get().uri(url)
                .headers(headers -> {
                    headers.set(HttpHeaders.ACCEPT_ENCODING, "gzip");
                    if (etag != null) {
                        headers.set(HttpHeaders.IF_NONE_MATCH, etag);
                    }
                })
                .exchange((request, response) -> {
                    if (response.getStatusCode().isSameCodeAs(HttpStatus.NOT_MODIFIED)) {
                        return new RemoteBody(true, etag, new byte[0]);
                    }
                    if (!response.getStatusCode().is2xxSuccessful()) {
                        throw new IOException("HTTP " + response.getStatusCode().value());
                    }
                    boolean isGzip = "gzip".equalsIgnoreCase(response.getHeaders().getFirst(HttpHeaders.CONTENT_ENCODING));
                    try (InputStream body = isGzip ? new GZIPInputStream(response.getBody()) : response.getBody()) {
                        return new RemoteBody(false, response.getHeaders().getETag(), body.readAllBytes());
                    }
                });
    }

    /** 생성기 HTTP 응답 — 304면 {@code isNotModified}가 true이고 본문은 비어 있다. */
    private record RemoteBody(boolean isNotModified, String etag, byte[] body) {
    }

    private JsonNode remember(String source, long version, JsonNode root) {
        cachedSource = source;
        cachedVersion = version;
        cachedAtMillis = System.currentTimeMillis();
        cachedRoot = root;
        return root;
    }

    // 생성기 GET /market-data 주소에서 GET /price-history 주소를 만든다(같은 생성기 HTTP 서버).
    private String priceHistoryUrl() {
        int index = localDataUrl.lastIndexOf("/market-data");
        return index >= 0 ? localDataUrl.substring(0, index) + "/price-history" : localDataUrl.replaceAll("/+$", "") + "/price-history";
    }

    private PriceHistoryFile readPriceHistoryFromUrl() {
        synchronized (priceHistoryLock) {
            return readPriceHistoryFromUrlLocked();
        }
    }

    private PriceHistoryFile readPriceHistoryFromUrlLocked() {
        long now = System.currentTimeMillis();
        if (cachedPriceHistory != null && now - cachedPriceHistoryFetchedAt < PRICE_HISTORY_URL_CACHE_MILLIS) {
            return cachedPriceHistory;
        }
        String url = priceHistoryUrl();
        try {
            RemoteBody remote = fetchRemote(url, cachedPriceHistory != null ? cachedPriceHistoryEtag : null);
            if (!remote.isNotModified()) {
                cachedPriceHistory = remote.body().length == 0
                        ? PriceHistoryFile.EMPTY : normalizeWeekMonthVolumes(OBJECT_MAPPER.readValue(remote.body(), PriceHistoryFile.class));
                cachedPriceHistoryEtag = remote.etag();
            }
        } catch (Exception e) {
            log.warn("원격 과거 시세 조회 실패 - url: {}, 사유: {}", url, e.getMessage());
            if (cachedPriceHistory == null) {
                cachedPriceHistory = PriceHistoryFile.EMPTY;
            }
            // 실패하면 10분이 아니라 30초 뒤에 다시 받는다.
            cachedPriceHistoryFetchedAt = now - PRICE_HISTORY_URL_CACHE_MILLIS + PRICE_HISTORY_RETRY_MILLIS;
            return cachedPriceHistory;
        }
        cachedPriceHistoryFetchedAt = now;
        return cachedPriceHistory;
    }

    private PriceHistoryFile readPriceHistory() {
        synchronized (priceHistoryLock) {
            return readPriceHistoryLocked();
        }
    }

    private PriceHistoryFile readPriceHistoryLocked() {
        File file = new File(localDataPath, PRICE_HISTORY_FILE_NAME);
        if (!file.exists()) {
            cachedPriceHistory = PriceHistoryFile.EMPTY;
            cachedPriceHistoryModifiedAt = -1L;
            return cachedPriceHistory;
        }
        long modifiedAt = file.lastModified();
        if (cachedPriceHistory != null && modifiedAt == cachedPriceHistoryModifiedAt) {
            return cachedPriceHistory;
        }
        try {
            cachedPriceHistory = normalizeWeekMonthVolumes(OBJECT_MAPPER.readValue(file, PriceHistoryFile.class));
        } catch (IOException e) {
            log.warn("로컬 과거 시세 파일 파싱 실패 - path: {}, 사유: {}", file.getPath(), e.getMessage());
            cachedPriceHistory = PriceHistoryFile.EMPTY;
        }
        cachedPriceHistoryModifiedAt = modifiedAt;
        return cachedPriceHistory;
    }

    // price_history.json 구조 — {"generatedAt": "...", "stocks": {"005930": {"day": [...], "week": [...], "month": [...]}}}.
    // 봉 목록은 최신순이고 각 봉은 HistoricalPriceDto 필드명을 그대로 쓴다.
    static class PriceHistoryFile {
        static final PriceHistoryFile EMPTY = new PriceHistoryFile();
        Map<String, StockPriceHistory> stocks = Map.of();
    }

    static class StockPriceHistory {
        List<HistoricalPriceDto> day;
        List<HistoricalPriceDto> week;
        List<HistoricalPriceDto> month;
    }
}
