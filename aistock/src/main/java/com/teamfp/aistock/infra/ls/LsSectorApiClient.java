package com.teamfp.aistock.infra.ls;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.infra.ls.dto.LsThemeConstituentDto;
import com.teamfp.aistock.infra.ls.dto.LsThemeDto;

import lombok.extern.slf4j.Slf4j;

/**
 * LS증권 Open API [주식] 섹터 카테고리({@code /stock/sector})를 조회하는 클라이언트.
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
public class LsSectorApiClient extends LsApiClientSupport {

    private static final int MAX_CONSTITUENT_ITEMS = 10;
    private static final int MAX_HOT_THEME_ITEMS = 5;
    private static final int MAX_STOCK_THEME_ITEMS = 5;

    private final LsAccessTokenProvider accessTokenProvider;

    @Value("${ls.sector-url}")
    private String sectorUrl;

    // 테마명(String) → 테마코드(String) 캐시. 프로세스 생존 기간 동안만 유효하면 충분하므로
    // DB나 Redis에 새로 저장하지 않고 인스턴스 메모리에 캐싱한다(전체테마 목록은 자주 안 바뀜).
    private volatile Map<String, String> themeCodeCache;

    public LsSectorApiClient(LsAccessTokenProvider accessTokenProvider, RestClient.Builder restClientBuilder) {
        super(restClientBuilder);
        this.accessTokenProvider = accessTokenProvider;
    }

    /**
     * 테마명으로 구성종목+시세를 조회한다(전체테마 캐시에서 부분일치 검색 → 테마종목별시세조회).
     * 일치하는 테마를 못 찾거나 조회가 실패하면 빈 리스트를 반환한다.
     */
    public List<LsThemeConstituentDto> getThemeConstituentsByName(String themeName) {
        Optional<String> themeCode = resolveThemeCode(themeName);
        if (themeCode.isEmpty()) {
            return List.of();
        }
        return getThemeConstituents(themeCode.get());
    }

    /** 특정 종목이 어떤 테마들에 속하는지 조회한다(t1532). */
    public List<LsThemeDto> getThemesForStock(String stockCode) {
        try {
            String token = accessTokenProvider.issueAccessToken();
            Map<String, Object> requestBody = Map.of("t1532InBlock", Map.of("shcode", stockCode));

            Map<String, Object> response = call("t1532", requestBody, token);
            List<Map<String, Object>> outBlock = extractList(response, "t1532OutBlock");
            List<LsThemeDto> items = outBlock.stream()
                    .map(row -> LsThemeDto.builder()
                            .themeCode(stringOf(row.get("tmcode")))
                            .themeName(stringOf(row.get("tmname")))
                            .build())
                    .toList();
            return items.size() > MAX_STOCK_THEME_ITEMS ? items.subList(0, MAX_STOCK_THEME_ITEMS) : items;
        } catch (CustomException e) {
            log.warn("LS 종목별테마 조회 중 오류 - stockCode: {}, 사유: {}", stockCode, e.getMessage());
            return List.of();
        }
    }

    /** 오늘 상승률·거래량이 두드러진 핫테마를 조회한다(t1533). */
    public List<LsThemeDto> getHotThemes() {
        try {
            String token = accessTokenProvider.issueAccessToken();
            Map<String, Object> requestBody = Map.of("t1533InBlock", Map.of("gubun", "1", "chgdate", 0));

            Map<String, Object> response = call("t1533", requestBody, token);
            List<Map<String, Object>> outBlock = extractList(response, "t1533OutBlock1");
            List<LsThemeDto> items = outBlock.stream()
                    .map(row -> LsThemeDto.builder()
                            .themeCode(stringOf(row.get("tmcode")))
                            .themeName(stringOf(row.get("tmname")))
                            .stats("상승 %s/%s종목, 상승률 %s%%, 거래증가율 %s%%".formatted(
                                    stringOf(row.get("upcnt")), stringOf(row.get("totcnt")),
                                    stringOf(row.get("uprate")), stringOf(row.get("diff_vol"))))
                            .build())
                    .toList();
            return items.size() > MAX_HOT_THEME_ITEMS ? items.subList(0, MAX_HOT_THEME_ITEMS) : items;
        } catch (CustomException e) {
            log.warn("LS 특이테마 조회 중 오류 - 사유: {}", e.getMessage());
            return List.of();
        }
    }

    private List<LsThemeConstituentDto> getThemeConstituents(String themeCode) {
        try {
            String token = accessTokenProvider.issueAccessToken();
            Map<String, Object> requestBody = Map.of("t1537InBlock", Map.of("tmcode", themeCode));

            Map<String, Object> response = call("t1537", requestBody, token);
            List<Map<String, Object>> outBlock = extractList(response, "t1537OutBlock1");
            List<LsThemeConstituentDto> items = outBlock.stream()
                    .map(row -> LsThemeConstituentDto.builder()
                            .stockCode(stringOf(row.get("shcode")))
                            .stockName(stringOf(row.get("hname")))
                            .price(parseLong(row.get("price")))
                            .changeAmount(parseLong(row.get("change")))
                            .changeRate(parseDoubleOrZero(row.get("diff")))
                            .volume(parseLong(row.get("volume")))
                            .build())
                    .toList();
            return items.size() > MAX_CONSTITUENT_ITEMS ? items.subList(0, MAX_CONSTITUENT_ITEMS) : items;
        } catch (CustomException e) {
            log.warn("LS 테마종목별시세 조회 중 오류 - themeCode: {}, 사유: {}", themeCode, e.getMessage());
            return List.of();
        }
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
        try {
            String token = accessTokenProvider.issueAccessToken();
            Map<String, Object> requestBody = Map.of("t8425InBlock", Map.of("dummy", ""));
            Map<String, Object> response = call("t8425", requestBody, token);

            Object outBlockObj = response != null ? response.get("t8425OutBlock") : null;
            if (!(outBlockObj instanceof List)) {
                log.warn("LS 전체테마 응답에서 t8425OutBlock을 찾지 못함 - 응답: {}", response);
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
        } catch (CustomException e) {
            log.warn("LS 전체테마 캐시 로딩 실패 - 사유: {}", e.getMessage());
            // 이번 요청은 빈 맵으로 처리하되, 캐시 필드 자체는 채우지 않아 다음 요청에서
            // 다시 시도할 수 있게 한다(일시적 장애일 수 있음 — DartApiClient의 corp_code 캐싱과
            // 동일한 방어 원칙).
            return Map.of();
        }
    }

    private Map<String, Object> call(String trCd, Map<String, Object> requestBody, String token) {
        return call(sectorUrl, trCd, requestBody, token, "LS 섹터(" + trCd + ") 조회 실패");
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
