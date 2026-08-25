package com.teamfp.aistock.manualcheck;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import com.teamfp.aistock.global.util.NewsRelevanceMatcher;
import com.teamfp.aistock.infra.naver.NaverNewsApiClient;
import com.teamfp.aistock.infra.naver.dto.NaverNewsSearchResponse;

/**
 * 일회성 수동 라이브 점검용 — feature/ai-news의 언론사 선택지 29곳 전부에 대해
 * searchByOutlet()이 실제로 "증시" 기사를 얼마나 찾아내는지 확인한다(2026-08-24 사용자 요청,
 * "이상한 건 빼자"는 논의의 근거 데이터 수집용). Gemini는 호출하지 않는다(비용 절감,
 * 이 점검은 커버리지 확인이 목적이라 요약까지는 필요 없음). CI 자동 실행 금지.
 */
class LiveOutletCoverageManualCheck {

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_LIVE_GEMINI_TEST", matches = "true")
    void checkOutletCoverage() {
        NaverNewsApiClient naverNewsApiClient = new NaverNewsApiClient(RestClient.builder());
        ReflectionTestUtils.setField(naverNewsApiClient, "clientId", System.getenv("NAVER_CLIENT_ID"));
        ReflectionTestUtils.setField(naverNewsApiClient, "clientSecret", System.getenv("NAVER_CLIENT_SECRET"));
        ReflectionTestUtils.setField(naverNewsApiClient, "apiUrl", "https://naverapihub.apigw.ntruss.com/search/v1/news");

        for (Map.Entry<String, String> entry : NewsRelevanceMatcher.OUTLET_NAMES.entrySet()) {
            String domain = entry.getKey();
            String outletName = entry.getValue();
            NaverNewsSearchResponse response = naverNewsApiClient.searchByOutlet(domain);
            System.out.println("=== " + outletName + " (" + domain + ") - " + response.results().size() + "건 ===");
            for (NaverNewsSearchResponse.NaverNewsResult result : response.results()) {
                System.out.println("  - " + result.title());
            }
        }
    }
}
