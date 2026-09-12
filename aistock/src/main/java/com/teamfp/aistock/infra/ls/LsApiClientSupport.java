package com.teamfp.aistock.infra.ls;

import java.util.Map;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import com.teamfp.aistock.global.util.ExternalApiInvoker;

/**
 * LS증권 Open API 클라이언트 10개(LsEtcApiClient 등)가 공유하는 요청 빌딩(Authorization/tr_cd/
 * tr_cont 헤더 + ExternalApiInvoker 위임)과 응답 필드 파싱(stringOf/parseLong/parseDouble)
 * 보일러플레이트를 한 곳에 모은 추상 베이스 클래스다(코드리뷰 반영 — 원래는 10개 파일에 거의
 * 동일한 코드가 그대로 복붙돼 있었다). LS 헤더 계약이 바뀌면(예: 새 tr_cont_key 요구) 여기
 * 한 곳만 고치면 된다.
 */
abstract class LsApiClientSupport {

    protected final RestClient restClient;
    private final LsQuoteCache quoteCache = new LsQuoteCache(java.time.Clock.systemUTC());
    private record QueryKey(String url, String tr, Map<String, Object> body, Map<String, String> headers) {}

    protected LsApiClientSupport(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
    }

    protected Map<String, Object> call(String url, String trCd, Map<String, Object> requestBody, String token, String errorLabel) {
        return call(url, trCd, requestBody, token, errorLabel, Map.of());
    }

    /**
     * extraHeaders — LsEtcApiClient만 tr_cont_key 헤더를 추가로 보낸다. 대다수 클라이언트는
     * 빈 맵(위 3-인자 오버로드)을 쓰고, 이 클라이언트만 명시적으로 채워 넘긴다 — 헤더 계약이
     * 파일마다 실제로 다르므로, 그 차이를 숨기지 않고 호출부에서 드러낸다.
     */
    protected Map<String, Object> call(
            String url, String trCd, Map<String, Object> requestBody, String token, String errorLabel,
            Map<String, String> extraHeaders) {
        long ttl = switch (trCd) {
            case "t1102", "t1511", "t1901" -> 2_000;
            case "t1452", "t1463", "t1441", "t1444" -> 10_000;
            case "t1305" -> 60_000;
            default -> 0;
        };
        QueryKey key = new QueryKey(url, trCd, requestBody, extraHeaders);
        Map<String, Object> cached = ttl > 0 ? quoteCache.get(key) : null;
        if (cached != null) return cached;
        Map<String, Object> result = ExternalApiInvoker.call(() -> {
            RestClient.RequestBodySpec spec = restClient.post()
                    .uri(url)
                    .header("Authorization", "Bearer " + token)
                    .header("tr_cd", trCd)
                    .header("tr_cont", "N");
            extraHeaders.forEach(spec::header);
            return spec.contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() { });
        }, errorLabel);
        quoteCache.put(key, result, ttl);
        return result;
    }

    protected String stringOf(Object value) {
        return value != null ? value.toString() : null;
    }

    protected Long parseLong(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // 이름을 parseDouble이 아니라 parseDoubleOrZero로 둔 이유 — LsInvestorTrendApiClient는
    // 실패/누락 시 null을 돌려주는 별도의 parseDouble(Object): Double을 그대로 쓰고 있어
    // (그 파일만 "값 없음"과 "0"을 구분해야 함), 이름이 같으면 반환 타입만 다른 두 메서드가
    // 같은 클래스 계층에 공존하게 돼 컴파일 에러가 난다. "실패 시 0.0"이라는 이 메서드의 계약을
    // 이름에 그대로 드러내 두 계약을 명확히 구분한다.
    // per/pbrx/exhratio(LsMarketDataApiClient)·per/exhratio(LsEtfApiClient)는 값이 없으면
    // (비교/우선주, ETF의 PBR 등) 0.0으로 뭉개지 않고 null로 남겨, describe 단계에서
    // "정보없음"으로 자연스럽게 안내할 수 있게 한다 — changeRate(diff)와 달리 "0"과 "값 없음"을
    // 구분해야 하는 지표라서 parseDoubleOrZero()와 분리한다. 원래 LsMarketDataApiClient에만
    // private으로 있었는데, LsEtfApiClient(t1901)도 동일 파싱이 필요해져 이 공통 베이스로
    // 올렸다(2026-09 코드리뷰 반영 — 두 클라이언트에 복붙하지 않기 위함).
    protected Double parseNullableDouble(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Double.parseDouble(value.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    protected double parseDoubleOrZero(Object value) {
        if (value == null) {
            return 0.0;
        }
        try {
            return Double.parseDouble(value.toString().trim());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
}
