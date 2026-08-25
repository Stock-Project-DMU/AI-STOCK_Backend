package com.teamfp.aistock.manualcheck;

import java.net.URI;
import java.util.List;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;

/**
 * 일회성 수동 라이브 점검용 — 조선비즈/이코노미스트가 계속 0건으로 나오는 이유가 도메인
 * 문자열 불일치 때문인지 확인한다. "증시" 검색 결과 500건 전체에서 실제로 잡히는 호스트
 * 목록을 전부 뽑아서, "chosun"이나 "economist"가 들어간 호스트가 있는지 육안으로 대조한다
 * (2026-08-24 사용자 요청). CI 자동 실행 금지.
 */
class LiveDomainMismatchProbeManualCheck {

    private static final int PAGE_SIZE = 100;
    private static final int PAGE_COUNT = 5;

    private record NaverApiItem(String title, String originallink, String link) {
    }

    private record NaverApiResponse(List<NaverApiItem> items) {
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_LIVE_GEMINI_TEST", matches = "true")
    void listAllHosts() {
        MappingJackson2HttpMessageConverter jsonEvenAsTextPlain = new MappingJackson2HttpMessageConverter();
        jsonEvenAsTextPlain.setSupportedMediaTypes(List.of(MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN));
        RestClient restClient = RestClient.builder()
                .messageConverters(converters -> converters.add(0, jsonEvenAsTextPlain))
                .build();

        String clientId = System.getenv("NAVER_CLIENT_ID");
        String clientSecret = System.getenv("NAVER_CLIENT_SECRET");

        TreeMap<String, Integer> hostCounts = new TreeMap<>();

        for (int page = 0; page < PAGE_COUNT; page++) {
            int start = 1 + page * PAGE_SIZE;
            NaverApiResponse response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .scheme("https")
                            .host("naverapihub.apigw.ntruss.com")
                            .path("/search/v1/news")
                            .queryParam("query", "증시")
                            .queryParam("display", PAGE_SIZE)
                            .queryParam("start", start)
                            .queryParam("sort", "date")
                            .queryParam("format", "json")
                            .build())
                    .header("X-NCP-APIGW-API-KEY-ID", clientId)
                    .header("X-NCP-APIGW-API-KEY", clientSecret)
                    .retrieve()
                    .body(NaverApiResponse.class);

            if (response == null || response.items() == null) break;

            for (NaverApiItem item : response.items()) {
                String source = item.originallink() != null && !item.originallink().isBlank() ? item.originallink() : item.link();
                if (source == null) continue;
                try {
                    String host = URI.create(source).getHost();
                    if (host != null) {
                        hostCounts.merge(host, 1, Integer::sum);
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
        }

        System.out.println("=== 전체 고유 호스트 목록 (" + hostCounts.size() + "개) ===");
        hostCounts.forEach((host, count) -> System.out.println("  " + host + " - " + count + "건"));

        System.out.println("=== chosun/economist 포함 호스트만 ===");
        hostCounts.entrySet().stream()
                .filter(e -> e.getKey().toLowerCase().contains("chosun") || e.getKey().toLowerCase().contains("economist"))
                .forEach(e -> System.out.println("  " + e.getKey() + " - " + e.getValue() + "건"));

        System.out.println("=== biz.chosun.com / economist.co.kr 실제 기사 제목 ===");
        for (int page = 0; page < PAGE_COUNT; page++) {
            int start = 1 + page * PAGE_SIZE;
            NaverApiResponse response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .scheme("https")
                            .host("naverapihub.apigw.ntruss.com")
                            .path("/search/v1/news")
                            .queryParam("query", "증시")
                            .queryParam("display", PAGE_SIZE)
                            .queryParam("start", start)
                            .queryParam("sort", "date")
                            .queryParam("format", "json")
                            .build())
                    .header("X-NCP-APIGW-API-KEY-ID", clientId)
                    .header("X-NCP-APIGW-API-KEY", clientSecret)
                    .retrieve()
                    .body(NaverApiResponse.class);
            if (response == null || response.items() == null) break;
            for (NaverApiItem item : response.items()) {
                String source = item.originallink() != null && !item.originallink().isBlank() ? item.originallink() : item.link();
                if (source == null) continue;
                try {
                    String host = URI.create(source).getHost();
                    if (host != null && (host.equals("biz.chosun.com") || host.equals("economist.co.kr"))) {
                        System.out.println("  [" + host + "] " + item.title());
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
    }
}
