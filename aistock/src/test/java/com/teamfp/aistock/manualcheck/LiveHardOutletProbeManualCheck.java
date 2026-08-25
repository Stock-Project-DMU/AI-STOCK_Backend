package com.teamfp.aistock.manualcheck;

import java.net.URI;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;

import com.teamfp.aistock.global.util.NewsRelevanceMatcher;

/**
 * 일회성 수동 라이브 점검용 — 디일렉/조선비즈/이코노미스트 3곳을 "증시" 외에 "코스피"/"주가"
 * 검색어로도 조회해서, 다른 검색어를 쓰면 시황 관련 기사가 잡히는지 확인한다(2026-08-24 사용자
 * 요청 — 목록에서 빼지 말고 3곳도 살릴 방법을 계속 찾아볼 것). CI 자동 실행 금지.
 */
class LiveHardOutletProbeManualCheck {

    private static final List<String> TARGET_DOMAINS = List.of("thelec.kr", "biz.chosun.com", "economist.co.kr");
    private static final List<String> QUERIES = List.of("증시", "코스피", "주가", "코스닥");
    private static final int PAGE_SIZE = 100;
    private static final int PAGE_COUNT = 5;

    private record NaverApiItem(String title, String originallink, String link) {
    }

    private record NaverApiResponse(List<NaverApiItem> items) {
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_LIVE_GEMINI_TEST", matches = "true")
    void probeAlternateQueries() {
        MappingJackson2HttpMessageConverter jsonEvenAsTextPlain = new MappingJackson2HttpMessageConverter();
        jsonEvenAsTextPlain.setSupportedMediaTypes(List.of(MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN));
        RestClient restClient = RestClient.builder()
                .messageConverters(converters -> converters.add(0, jsonEvenAsTextPlain))
                .build();

        String clientId = System.getenv("NAVER_CLIENT_ID");
        String clientSecret = System.getenv("NAVER_CLIENT_SECRET");

        for (String query : QUERIES) {
            System.out.println("########## 검색어: " + query + " ##########");
            for (int page = 0; page < PAGE_COUNT; page++) {
                int start = 1 + page * PAGE_SIZE;
                final String q = query;
                NaverApiResponse response = restClient.get()
                        .uri(uriBuilder -> uriBuilder
                                .scheme("https")
                                .host("naverapihub.apigw.ntruss.com")
                                .path("/search/v1/news")
                                .queryParam("query", q)
                                .queryParam("display", PAGE_SIZE)
                                .queryParam("start", start)
                                .queryParam("sort", "date")
                                .queryParam("format", "json")
                                .build())
                        .header("X-NCP-APIGW-API-KEY-ID", clientId)
                        .header("X-NCP-APIGW-API-KEY", clientSecret)
                        .retrieve()
                        .body(NaverApiResponse.class);

                if (response == null || response.items() == null || response.items().isEmpty()) {
                    break;
                }

                for (NaverApiItem item : response.items()) {
                    String source = item.originallink() != null && !item.originallink().isBlank() ? item.originallink() : item.link();
                    if (source == null) continue;
                    String host;
                    try {
                        host = URI.create(source).getHost();
                    } catch (IllegalArgumentException e) {
                        continue;
                    }
                    if (host == null) continue;

                    for (String domain : TARGET_DOMAINS) {
                        if (NewsRelevanceMatcher.matchesDomain(host, domain)) {
                            boolean marketRelevant = NewsRelevanceMatcher.isMarketRelevant(item.title());
                            System.out.println("  [" + domain + "] (관련성필터 " + (marketRelevant ? "통과" : "탈락") + ") " + item.title());
                        }
                    }
                }
            }
        }
    }
}
