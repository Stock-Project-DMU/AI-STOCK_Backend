package com.teamfp.aistock.manualcheck;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;

import com.teamfp.aistock.global.util.NewsRelevanceMatcher;

/**
 * 일회성 수동 라이브 점검용 — 오늘 기사 0건이었던 16개 언론사가 진짜로 기사가 없는 건지,
 * 아니면 지금 방식(단일 페이지 100건)이 놓치고 있는 건지 확인한다. 네이버 뉴스 검색 API의
 * start 파라미터로 여러 페이지(최대 5페이지 = 500건)를 더 가져와서, 0건이었던 언론사가
 * 뒤쪽 페이지에 있는지 탐색한다(2026-08-24 사용자 요청). CI 자동 실행 금지.
 */
class LiveOutletPaginationProbeManualCheck {

    private static final List<String> ZERO_RESULT_OUTLET_DOMAINS = List.of(
            "sedaily.com", "asiae.co.kr", "thelec.kr", "techm.kr", "dt.co.kr", "etnews.com",
            "biz.chosun.com", "news1.kr", "bizwatch.co.kr", "businesspost.co.kr", "economist.co.kr",
            "wowtv.co.kr", "ajunews.com", "heraldcorp.com", "imnews.imbc.com", "news.sbs.co.kr");

    private static final int PAGE_SIZE = 100;
    private static final int PAGE_COUNT = 5; // start=1,101,201,301,401 → 최대 500건까지 탐색

    private record NaverApiItem(String title, String originallink, String link, String pubDate) {
    }

    private record NaverApiResponse(List<NaverApiItem> items) {
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_LIVE_GEMINI_TEST", matches = "true")
    void probeDeepPages() {
        MappingJackson2HttpMessageConverter jsonEvenAsTextPlain = new MappingJackson2HttpMessageConverter();
        jsonEvenAsTextPlain.setSupportedMediaTypes(List.of(MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN));
        RestClient restClient = RestClient.builder()
                .messageConverters(converters -> converters.add(0, jsonEvenAsTextPlain))
                .build();

        String clientId = System.getenv("NAVER_CLIENT_ID");
        String clientSecret = System.getenv("NAVER_CLIENT_SECRET");

        Map<String, Integer> hitsByDomain = new LinkedHashMap<>();
        ZERO_RESULT_OUTLET_DOMAINS.forEach(domain -> hitsByDomain.put(domain, 0));
        Map<String, String> firstTitleByDomain = new LinkedHashMap<>();
        Map<String, String> newestPubDateByDomain = new LinkedHashMap<>();
        Map<String, String> oldestPubDateByDomain = new LinkedHashMap<>();

        int totalFetched = 0;
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

            if (response == null || response.items() == null || response.items().isEmpty()) {
                System.out.println("=== start=" + start + " - 응답 없음, 중단 ===");
                break;
            }
            totalFetched += response.items().size();

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

                for (String domain : ZERO_RESULT_OUTLET_DOMAINS) {
                    if (NewsRelevanceMatcher.matchesDomain(host, domain)) {
                        hitsByDomain.merge(domain, 1, Integer::sum);
                        firstTitleByDomain.putIfAbsent(domain, item.title());
                        // 결과가 date 정렬(최신순)이라, 같은 언론사에서 처음 만나는 게 그
                        // 언론사 기준 "가장 최신" 매칭이고, 마지막에 만나는 게 500건 범위 안에서
                        // "가장 오래된" 매칭이다.
                        newestPubDateByDomain.putIfAbsent(domain, item.pubDate());
                        oldestPubDateByDomain.put(domain, item.pubDate());
                    }
                }
            }
        }

        System.out.println("=== 총 " + totalFetched + "건 탐색 (start 1~" + (1 + (PAGE_COUNT - 1) * PAGE_SIZE) + ") ===");
        for (String domain : ZERO_RESULT_OUTLET_DOMAINS) {
            String outletName = NewsRelevanceMatcher.OUTLET_NAMES.getOrDefault(domain, domain);
            int hits = hitsByDomain.get(domain);
            System.out.println("=== " + outletName + " (" + domain + ") - " + hits + "건 ===");
            if (hits > 0) {
                System.out.println("  첫 매칭 제목: " + firstTitleByDomain.get(domain));
                System.out.println("  가장 최신: " + newestPubDateByDomain.get(domain));
                System.out.println("  가장 오래됨(500건 범위 내): " + oldestPubDateByDomain.get(domain));
            }
        }
    }
}
