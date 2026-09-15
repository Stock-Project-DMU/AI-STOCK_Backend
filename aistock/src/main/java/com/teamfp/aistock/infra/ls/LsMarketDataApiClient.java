package com.teamfp.aistock.infra.ls;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.infra.ls.dto.LsCallAuctionPriceDto;
import com.teamfp.aistock.infra.ls.dto.LsCurrentPriceDetailDto;
import com.teamfp.aistock.infra.ls.dto.LsHistoricalPriceDto;
import com.teamfp.aistock.infra.ls.dto.LsMultiStockPriceDto;
import com.teamfp.aistock.infra.ls.dto.LsPivotLevelDto;
import com.teamfp.aistock.infra.ls.dto.LsStockRiskFlagDto;

import lombok.extern.slf4j.Slf4j;

/**
 * LS증권 Open API로 종목 현재가(+PER/PBR/52주 최고·최저 등 시세 종합 정보)를 그때그때
 * (on-demand) REST로 조회하는 클라이언트.
 *
 * {@link LsWebSocketClient}(실시간 tick 스트림)와는 목적이 다르다 — WebSocket은 subscribe()한
 * 종목만 계속 흘러들어오는 구조라, AI 상담처럼 "아무 회사나 갑자기 물어볼 수 있는" 용도에는
 * 맞지 않는다(2026-08-07 확인 — subscribe() 호출부가 아직 어디에도 연결돼 있지 않아, 사실상
 * 어떤 종목도 실시간 캐시가 채워지지 않는 상태였다). 그래서 AI의 get_current_price 도구는
 * WebSocket 캐시 대신 이 REST 클라이언트로 그때그때 직접 조회한다.
 *
 * {@link LsWebSocketClient}와 달리 {@code ls.mode} 조건 없이 항상 빈으로 생성된다 — 지속
 * 연결을 유지하는 게 아니라 요청마다 독립적으로 호출/실패하는 구조라, GeminiApiClient/
 * DartApiClient와 동일하게 키가 비어 있어도 그냥 실패 응답으로 처리되면 되기 때문이다(코드
 * 수정 없이 키만 나중에 채워 넣어도 그대로 동작).
 *
 * <p>t1102 응답 필드명은 2026-08-07엔 문서 없이 추정으로 작성했는데(당시 삼성전자 실제가로
 * 검증 완료), 이후 LS 공식 API 카탈로그 문서를 확보해 필드명 전체(166개)를 대조한 결과
 * hname/price/sign/change/diff/volume 등 이미 쓰던 필드명은 전부 정확했다. 이번엔 그 문서를
 * 근거로 PER/PBR/52주 최고·최저·상장주식수·소진율까지 추가로 파싱한다(2026-08-10).</p>
 *
 * <p>다만 sign 필드는 이름만 알고 있었을 뿐 실제로 읽어 쓰지는 않고 있었다 — change(등락액)가
 * 부호 없는 크기로 오는 걸 그대로 changeAmount에 넣어, 하락 종목도 항상 양수로 저장되는 버그가
 * 있었다(local-market-data-generator 작업 중 실제 LS 응답으로 실측: sign=5(하락)인데
 * change=9500(양수), diff=-3.53(음수) — 서로 모순). {@link LsApiClientSupport#signedLong} 추가로
 * 수정(2026-09-11) — 같은 버그가 t8407(이 클래스의 {@code getMultiStockPrices})과 다른 5개
 * 클라이언트(LsEtfApiClient/LsHighItemApiClient/LsInvestInfoApiClient/LsIndustryApiClient/
 * LsSectorApiClient)에도 있어 전부 같은 방식으로 함께 고쳤다.</p>
 *
 * <p>{@code getCurrentPrice()}만 {@code ls.mode=mock}에서 {@link LsLocalMarketDataReader}(로컬
 * 파일)로 전환된다(feature/ls-local-data, 2026-08-30). 이 클래스의 나머지 메서드(t1105/t1305/
 * t8407/t1404+t1405/t1486)와 다른 9개 REST 클라이언트(t1716/t3401 등)는 {@link
 * LsAccessTokenProvider}와 동일하게 ls.mode와 무관하게 항상 실제 LS API를 호출한다 — mock 전환
 * 대상은 이번 범위에서 t1102(현재가) 하나로 한정한다.</p>
 */
@Slf4j
@Component
public class LsMarketDataApiClient extends LsApiClientSupport {

    private static final String CURRENT_PRICE_TR_CD = "t1102";
    private static final String OUT_BLOCK_KEY = "t1102OutBlock";

    private final LsAccessTokenProvider accessTokenProvider;
    private final Optional<LsLocalMarketDataReader> localMarketDataReader;

    @Value("${ls.market-data-url}")
    private String marketDataUrl;

    public LsMarketDataApiClient(
            LsAccessTokenProvider accessTokenProvider,
            Optional<LsLocalMarketDataReader> localMarketDataReader,
            RestClient.Builder restClientBuilder) {
        super(restClientBuilder);
        this.accessTokenProvider = accessTokenProvider;
        this.localMarketDataReader = localMarketDataReader;
    }

    /**
     * 종목코드로 현재가(장중이면 실시간 체결가, 장 마감 후라면 LS가 돌려주는 마지막 체결가)와
     * PER/PBR/52주 최고·최저·상장주식수·소진율을 함께 조회한다. 토큰 발급 실패, TR 호출 실패,
     * 응답 구조가 예상과 다른 경우 전부 빈 값을 반환한다 — 호출자(AiPlanningService)가
     * "확인할 수 없다"로 자연스럽게 처리하도록 예외를 던지지 않는다.
     *
     * <p>{@code ls.mode=mock}이면(={@code localMarketDataReader}가 존재하면) LS API를 호출하지
     * 않고 로컬 파일 조회로 대체한다. 로컬 파일이 없거나 파싱에 실패해도 실제 LS API로 폴백하지
     * 않고 그대로 빈 값을 반환한다 — mock 모드에서는 로컬 파일이 유일한 데이터 소스다.</p>
     */
    public Optional<LsCurrentPriceDetailDto> getCurrentPrice(String stockCode) {
        if (localMarketDataReader.isPresent()) {
            return localMarketDataReader.get().getCurrentPrice(stockCode);
        }
        try {
            String token = accessTokenProvider.issueAccessToken();
            Map<String, Object> requestBody = Map.of("t1102InBlock", Map.of("shcode", stockCode));

            Map<String, Object> response = call(marketDataUrl, CURRENT_PRICE_TR_CD, requestBody, token, "LS 현재가 조회 실패");

            return parseCurrentPrice(stockCode, response);
        } catch (CustomException e) {
            log.warn("LS 현재가 조회 중 오류 - stockCode: {}, 사유: {}", stockCode, e.getMessage());
            return Optional.empty();
        }
    }

    @SuppressWarnings("unchecked")
    private Optional<LsCurrentPriceDetailDto> parseCurrentPrice(String stockCode, Map<String, Object> response) {
        if (response == null) {
            return Optional.empty();
        }
        Object outBlockObj = response.get(OUT_BLOCK_KEY);
        if (!(outBlockObj instanceof Map)) {
            log.warn("LS 현재가 응답에서 {}을 찾지 못함 - stockCode: {}, 응답: {}", OUT_BLOCK_KEY, stockCode, response);
            return Optional.empty();
        }
        Map<String, Object> outBlock = (Map<String, Object>) outBlockObj;

        Long currentPrice = parseLong(outBlock.get("price"));
        if (currentPrice == null) {
            log.warn("LS 현재가 응답에서 price를 파싱하지 못함 - stockCode: {}, outBlock: {}", stockCode, outBlock);
            return Optional.empty();
        }
        Long changeAmount = signedLong(outBlock.get("change"), outBlock.get("sign"));
        Long volume = parseLong(outBlock.get("volume"));

        return Optional.of(LsCurrentPriceDetailDto.builder()
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
    // 요청 시 월봉(dwmcode=3)으로 전환한다. LS API가 지원하는 최대 기간을 2년(뉴스 검색과
    // 동일한 상한, CLAUDE.md 팀 합의)으로 맞춰 월봉 24개까지 허용한다.
    private static final int MAX_PERIOD_MONTHS = 24;
    private static final int DWMCODE_DAY = 1;
    private static final int DWMCODE_MONTH = 3;

    /** 관리종목(t1404)+투자경고/매매정지(t1405) 여부를 함께 확인한다(2026-08-11 추가). */
    public List<LsStockRiskFlagDto> getRiskFlags(String stockCode) {
        List<LsStockRiskFlagDto> flags = new java.util.ArrayList<>();
        flags.addAll(fetchRiskFlags("t1404", "t1404OutBlock1", stockCode, "관리종목"));
        flags.addAll(fetchRiskFlags("t1405", "t1405OutBlock1", stockCode, "투자경고/매매정지"));
        return flags;
    }

    @SuppressWarnings("unchecked")
    private List<LsStockRiskFlagDto> fetchRiskFlags(String trCd, String outBlockKey, String stockCode, String flagType) {
        try {
            String token = accessTokenProvider.issueAccessToken();
            Map<String, Object> inBlock = Map.of("gubun", "0", "jongchk", "1", "cts_shcode", " ");
            Map<String, Object> requestBody = Map.of(trCd + "InBlock", inBlock);

            Map<String, Object> response = call(marketDataUrl, trCd, requestBody, token, "LS 위험신호(" + trCd + ") 조회 실패");

            if (response == null || !(response.get(outBlockKey) instanceof List)) {
                return List.of();
            }
            List<Map<String, Object>> outBlock = (List<Map<String, Object>>) response.get(outBlockKey);
            return outBlock.stream()
                    .filter(row -> stockCode.equals(stringOf(row.get("shcode"))))
                    .map(row -> LsStockRiskFlagDto.builder()
                            .flagType(flagType)
                            .reasonCode(stringOf(row.get("reason")))
                            .date(stringOf(row.get("date")))
                            .build())
                    .toList();
        } catch (CustomException e) {
            log.warn("LS 위험신호({}) 조회 중 오류 - stockCode: {}, 사유: {}", trCd, stockCode, e.getMessage());
            return List.of();
        }
    }

    /** 피봇/디마크(t1105) — 전일 시고저 기준 지지·저항선을 조회한다(2026-08-11 추가). */
    public Optional<LsPivotLevelDto> getPivotLevels(String stockCode) {
        try {
            String token = accessTokenProvider.issueAccessToken();
            Map<String, Object> requestBody = Map.of("t1105InBlock", Map.of("shcode", stockCode, "exchgubun", "K"));

            Map<String, Object> response = call(marketDataUrl, "t1105", requestBody, token, "LS 피봇/디마크 조회 실패");

            if (response == null || !(response.get("t1105OutBlock") instanceof Map)) {
                return Optional.empty();
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> outBlock = (Map<String, Object>) response.get("t1105OutBlock");
            return Optional.of(LsPivotLevelDto.builder()
                    .stockCode(stockCode)
                    .pivot(parseLong(outBlock.get("pbot")))
                    .resistance1(parseLong(outBlock.get("offer1")))
                    .support1(parseLong(outBlock.get("supp1")))
                    .resistance2(parseLong(outBlock.get("offer2")))
                    .support2(parseLong(outBlock.get("supp2")))
                    .build());
        } catch (CustomException e) {
            log.warn("LS 피봇/디마크 조회 중 오류 - stockCode: {}, 사유: {}", stockCode, e.getMessage());
            return Optional.empty();
        }
    }

    /** 기간별주가(t1305) — 최근 며칠치 시세+시가총액+외국인/개인 순매수(2026-08-11 추가). */
    public List<LsHistoricalPriceDto> getRecentHistoricalPrices(String stockCode) {
        return getHistoricalPrices(stockCode, null);
    }

    /**
     * 기간별주가(t1305) — periodMonths가 없으면 기존과 동일하게 최근 5거래일(dwmcode=1 일봉)을
     * 반환한다. periodMonths가 있으면(6개월/1년처럼 장기간 저점·고점을 묻는 질문 대응,
     * 2026-08-13 추가) 일봉 대신 월봉(dwmcode=3)으로 전환해 최대 24개월(2년, 뉴스 검색과 동일한
     * 상한)까지 조회한다 — 일봉으로 2년을 요청하면 수백 건이 와서 토큰 낭비가 크고, LS가 이미
     * 월봉 모드를 지원하므로 그걸 그대로 쓴다.
     */
    public List<LsHistoricalPriceDto> getHistoricalPrices(String stockCode, Integer periodMonths) {
        boolean longPeriod = periodMonths != null && periodMonths > 0;
        int dwmcode = longPeriod ? DWMCODE_MONTH : DWMCODE_DAY;
        int cnt = longPeriod ? Math.min(periodMonths, MAX_PERIOD_MONTHS) : MAX_HISTORICAL_ITEMS;
        try {
            String token = accessTokenProvider.issueAccessToken();
            Map<String, Object> inBlock = new java.util.LinkedHashMap<>();
            inBlock.put("shcode", stockCode);
            inBlock.put("dwmcode", dwmcode);
            inBlock.put("date", "");
            inBlock.put("idx", 0);
            inBlock.put("cnt", cnt);
            Map<String, Object> requestBody = Map.of("t1305InBlock", inBlock);

            Map<String, Object> response = call(marketDataUrl, "t1305", requestBody, token, "LS 기간별주가 조회 실패");

            if (response == null || !(response.get("t1305OutBlock1") instanceof List)) {
                return List.of();
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> outBlock = (List<Map<String, Object>>) response.get("t1305OutBlock1");
            return outBlock.stream()
                    .limit(cnt)
                    .map(row -> LsHistoricalPriceDto.builder()
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
        } catch (CustomException e) {
            log.warn("LS 기간별주가 조회 중 오류 - stockCode: {}, 사유: {}", stockCode, e.getMessage());
            return List.of();
        }
    }

    /** API용주식멀티현재가조회(t8407) — 최대 5종목까지 한번에 현재가 조회(2026-08-11 추가). */
    public List<LsMultiStockPriceDto> getMultiStockPrices(List<String> stockCodes) {
        if (stockCodes == null || stockCodes.isEmpty()) {
            return List.of();
        }
        List<String> limited = stockCodes.size() > 5 ? stockCodes.subList(0, 5) : stockCodes;
        try {
            String token = accessTokenProvider.issueAccessToken();
            String concatenatedCodes = String.join("", limited);
            Map<String, Object> requestBody = Map.of("t8407InBlock",
                    Map.of("nrec", limited.size(), "shcode", concatenatedCodes));

            Map<String, Object> response = call(marketDataUrl, "t8407", requestBody, token, "LS 멀티종목현재가 조회 실패");

            if (response == null || !(response.get("t8407OutBlock1") instanceof List)) {
                return List.of();
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> outBlock = (List<Map<String, Object>>) response.get("t8407OutBlock1");
            return outBlock.stream()
                    .map(row -> LsMultiStockPriceDto.builder()
                            .stockCode(stringOf(row.get("shcode")))
                            .stockName(stringOf(row.get("hname")))
                            .price(parseLong(row.get("price")))
                            .changeAmount(signedLong(row.get("change"), row.get("sign")))
                            .changeRate(parseDoubleOrZero(row.get("diff")))
                            .volume(parseLong(row.get("volume")))
                            .build())
                    .toList();
        } catch (CustomException e) {
            log.warn("LS 멀티종목현재가 조회 중 오류 - stockCodes: {}, 사유: {}", stockCodes, e.getMessage());
            return List.of();
        }
    }

    /**
     * 시간별예상체결가(t1486) — 동시호가 시간대 종목별 예상체결가(2026-08-11 추가). 이 시간대가
     * 아니면 호출하기 전에 {@link com.teamfp.aistock.global.util.DateUtil#isCallAuctionTime()}로
     * 게이트를 걸어야 한다(호출부인 AiPlanningService의 책임 — 이 클라이언트 자체는 게이트를 모른다).
     */
    public List<LsCallAuctionPriceDto> getRecentCallAuctionPrices(String stockCode) {
        try {
            String token = accessTokenProvider.issueAccessToken();
            Map<String, Object> inBlock = new java.util.LinkedHashMap<>();
            inBlock.put("shcode", stockCode);
            inBlock.put("cts_time", "");
            inBlock.put("cnt", MAX_CALL_AUCTION_ITEMS);
            inBlock.put("exchgubun", "K");
            Map<String, Object> requestBody = Map.of("t1486InBlock", inBlock);

            Map<String, Object> response = call(marketDataUrl, "t1486", requestBody, token, "LS 동시호가예상체결가 조회 실패");

            if (response == null || !(response.get("t1486OutBlock1") instanceof List)) {
                return List.of();
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> outBlock = (List<Map<String, Object>>) response.get("t1486OutBlock1");
            return outBlock.stream()
                    .limit(MAX_CALL_AUCTION_ITEMS)
                    .map(row -> LsCallAuctionPriceDto.builder()
                            .time(stringOf(row.get("chetime")))
                            .price(parseLong(row.get("price")))
                            .changeRate(parseDoubleOrZero(row.get("diff")))
                            .expectedVolume(parseLong(row.get("cvolume")))
                            .build())
                    .toList();
        } catch (CustomException e) {
            log.warn("LS 동시호가예상체결가 조회 중 오류 - stockCode: {}, 사유: {}", stockCode, e.getMessage());
            return List.of();
        }
    }

    // per/pbrx/exhratio는 값이 없으면(비교/우선주 등) 0.0으로 뭉개지 않고 null로 남겨,
    // describe 단계에서 "정보없음"으로 자연스럽게 안내할 수 있게 한다 — changeRate(diff)와
    // 달리 이 세 필드는 "0"과 "값 없음"을 구분해야 하는 지표라서 parseDoubleOrZero()와 분리했다.
    private Double parseNullableDouble(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Double.parseDouble(value.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
