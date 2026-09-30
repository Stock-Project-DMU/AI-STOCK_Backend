package com.teamfp.aistock.infra.marketdata;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.teamfp.aistock.infra.marketdata.dto.ThemeConstituentDto;
import com.teamfp.aistock.infra.marketdata.dto.ThemeDto;

import lombok.extern.slf4j.Slf4j;

/**
 * 외부 시세 데이터 제공사 Open API [주식] 섹터 카테고리({@code /stock/sector})를 조회하는 클라이언트.
 * 전체테마(t8425)/종목별테마(t1532)/특이테마(t1533)/테마종목별시세조회(t1537) 4개 TR을 다룬다.
 *
 * <p>테마별종목(t1531)은 테마명 문자열을 그대로 서버에 넘겨 검색하는 방식인데, 실제 라이브
 * 호출로 확인한 결과(2026-08-10) 정확한 명칭이 아니면 빈 결과만 돌아와 신뢰도가 낮았다. 대신
 * 전체테마(t8425) 목록을 앱 시작 후 최초 요청 시 1회 캐싱해두고(DartApiClient의 corp_code.xml
 * 캐싱과 동일한 패턴 — 이 목록도 자주 안 바뀜) 사용자가 말한 테마명을 그 목록에서 부분일치로
 * 찾아 테마코드를 얻은 뒤, 테마종목별시세조회(t1537)로 구성종목+시세를 함께 받는 2단계 방식을
 * 쓴다 — 결과적으로 "테마별종목"이 하려던 일(테마명→구성종목)을 더 안정적으로 달성한다.</p>
 */
@Slf4j
@Component
public class SectorApiClient extends MarketDataApiClientSupport {

    private static final int MAX_CONSTITUENT_ITEMS = 10;
    private static final int MAX_HOT_THEME_ITEMS = 5;
    private static final int MAX_STOCK_THEME_ITEMS = 5;

    private final MarketDataAccessTokenProvider accessTokenProvider;

    @Value("${market-data.sector-url}")
    private String sectorUrl;

    // 테마명(String) → 테마코드(String) 캐시. 프로세스 생존 기간 동안만 유효하면 충분하므로
    // DB나 Redis에 새로 저장하지 않고 인스턴스 메모리에 캐싱한다(전체테마 목록은 자주 안 바뀜).
    private volatile Map<String, String> themeCodeCache;

    public SectorApiClient(MarketDataAccessTokenProvider accessTokenProvider, @org.springframework.beans.factory.annotation.Qualifier("marketDataRestClientBuilder") RestClient.Builder restClientBuilder) {
        super(restClientBuilder);
        this.accessTokenProvider = accessTokenProvider;
    }

    /**
     * 테마명으로 구성종목+시세를 조회한다(전체테마 캐시에서 부분일치 검색 → 테마종목별시세조회).
     * 일치하는 테마를 못 찾으면 빈 리스트를 반환하고, 외부 시세 데이터 호출 자체가 실패하면 CustomException(MARKET_DATA_UNAVAILABLE)을 던진다(#05).
     */
    public List<ThemeConstituentDto> getThemeConstituentsByName(String themeName) {
        Optional<String> themeCode = resolveThemeCode(themeName);
        if (themeCode.isEmpty()) {
            return List.of();
        }
        return getThemeConstituents(themeCode.get());
    }

    /** 특정 종목이 어떤 테마들에 속하는지 조회한다(t1532). */
    public List<ThemeDto> getThemesForStock(String stockCode) {
        String token = accessTokenProvider.issueAccessToken();
        Map<String, Object> requestBody = Map.of("t1532InBlock", Map.of("shcode", stockCode));

        Map<String, Object> response = call("t1532", requestBody, token);
        List<Map<String, Object>> outBlock = extractList(response, "t1532OutBlock");
        List<ThemeDto> items = outBlock.stream()
                .map(row -> ThemeDto.builder()
                        .themeCode(stringOf(row.get("tmcode")))
                        .themeName(stringOf(row.get("tmname")))
                        .build())
                .toList();
        return items.size() > MAX_STOCK_THEME_ITEMS ? items.subList(0, MAX_STOCK_THEME_ITEMS) : items;
    }

    /** 오늘 상승률·거래량이 두드러진 핫테마를 조회한다(t1533). */
    public List<ThemeDto> getHotThemes() {
        String token = accessTokenProvider.issueAccessToken();
        Map<String, Object> requestBody = Map.of("t1533InBlock", Map.of("gubun", "1", "chgdate", 0));

        Map<String, Object> response = call("t1533", requestBody, token);
        List<Map<String, Object>> outBlock = extractList(response, "t1533OutBlock1");
        List<ThemeDto> items = outBlock.stream()
                .map(row -> ThemeDto.builder()
                        .themeCode(stringOf(row.get("tmcode")))
                        .themeName(stringOf(row.get("tmname")))
                        .stats("상승 %s/%s종목, 상승률 %s%%, 거래증가율 %s%%".formatted(
                                stringOf(row.get("upcnt")), stringOf(row.get("totcnt")),
                                stringOf(row.get("uprate")), stringOf(row.get("diff_vol"))))
                        .build())
                .toList();
        return items.size() > MAX_HOT_THEME_ITEMS ? items.subList(0, MAX_HOT_THEME_ITEMS) : items;
    }

    private List<ThemeConstituentDto> getThemeConstituents(String themeCode) {
        String token = accessTokenProvider.issueAccessToken();
        Map<String, Object> requestBody = Map.of("t1537InBlock", Map.of("tmcode", themeCode));

        Map<String, Object> response = call("t1537", requestBody, token);
        List<Map<String, Object>> outBlock = extractList(response, "t1537OutBlock1");
        List<ThemeConstituentDto> items = outBlock.stream()
                .map(row -> ThemeConstituentDto.builder()
                        .stockCode(stringOf(row.get("shcode")))
                        .stockName(stringOf(row.get("hname")))
                        .price(parseLong(row.get("price")))
                        // change가 부호 없는 크기로 오는 t1102와 동일 패턴(t1537 실측:
                        // sign=5인데 change=12000 양수) — signedLong으로 수정(2026-09-11).
                        .changeAmount(signedLong(row.get("change"), row.get("sign")))
                        .changeRate(parseDoubleOrZero(row.get("diff")))
                        .volume(parseLong(row.get("volume")))
                        .build())
                .toList();
        return items.size() > MAX_CONSTITUENT_ITEMS ? items.subList(0, MAX_CONSTITUENT_ITEMS) : items;
    }

    private Optional<String> resolveThemeCode(String themeName) {
        if (themeName == null || themeName.isBlank()) {
            return Optional.empty();
        }
        Map<String, String> cache = themeCodeCache;
        if (cache == null) {
            cache = loadThemeCodeCache();
        }
        return cache.entrySet().stream()
                .filter(entry -> entry.getKey().contains(themeName) || themeName.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst();
    }

    @SuppressWarnings("unchecked")
    private synchronized Map<String, String> loadThemeCodeCache() {
        if (themeCodeCache != null) {
            return themeCodeCache;
        }
        String token = accessTokenProvider.issueAccessToken();
        Map<String, Object> requestBody = Map.of("t8425InBlock", Map.of("dummy", ""));
        Map<String, Object> response = call("t8425", requestBody, token);

        Object outBlockObj = response != null ? response.get("t8425OutBlock") : null;
        if (!(outBlockObj instanceof List)) {
            log.warn("외부 시세 데이터 전체테마 응답에서 t8425OutBlock을 찾지 못함 - 응답: {}", response);
            // 캐시 필드 자체는 채우지 않아 다음 요청에서 다시 시도할 수 있게 한다
            // (아래 catch 블록과 동일한 방어 원칙 — 일시적 응답 이상으로 영구 오염 방지).
            return Map.of();
        }
        List<Map<String, Object>> rows = (List<Map<String, Object>>) outBlockObj;
        Map<String, String> cache = new java.util.LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String name = stringOf(row.get("tmname"));
            String code = stringOf(row.get("tmcode"));
            if (name != null && code != null) {
                cache.put(name, code);
            }
        }
        themeCodeCache = cache;
        return themeCodeCache;
    }

    private Map<String, Object> call(String trCd, Map<String, Object> requestBody, String token) {
        return call(sectorUrl, trCd, requestBody, token, "외부 시세 데이터 섹터(" + trCd + ") 조회 실패");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractList(Map<String, Object> response, String key) {
        if (response == null) {
            return List.of();
        }
        Object obj = response.get(key);
        return obj instanceof List ? (List<Map<String, Object>>) obj : List.of();
    }
}
