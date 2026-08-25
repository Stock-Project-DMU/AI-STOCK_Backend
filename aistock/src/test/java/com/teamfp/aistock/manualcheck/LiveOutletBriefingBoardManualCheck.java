package com.teamfp.aistock.manualcheck;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.ObjectMapper;

import com.teamfp.aistock.global.util.NewsRelevanceMatcher;
import com.teamfp.aistock.infra.gemini.GeminiApiClient;
import com.teamfp.aistock.infra.gemini.dto.GeminiRequest;
import com.teamfp.aistock.infra.gemini.dto.GeminiResponse;
import com.teamfp.aistock.infra.naver.NaverNewsApiClient;
import com.teamfp.aistock.infra.naver.dto.NaverNewsSearchResponse;

/**
 * 일회성 수동 라이브 점검용 — 언론사 선택지 29곳 전부에 대해 실제 네이버 뉴스 API로 기사를
 * 모으고, 기사가 있는 언론사는 실제 Gemini로 브리핑까지 만들어 파일로 저장한다(2026-08-24
 * 사용자 요청, "각각 요약해서 현황판에 업데이트해봐 내용보고 내가 판단해봄"). AiNewsService의
 * 인사말/프롬프트 로직과 동일하게 맞춘다. CI 자동 실행 금지.
 */
class LiveOutletBriefingBoardManualCheck {

    private static final String BRIEFING_GREETING = "안녕하세요, AI 시황 비서 AI STOCK입니다. 오늘의 주요 시황을 브리핑해드리겠습니다.\n\n";

    private static final String SUMMARY_SYSTEM_INSTRUCTION =
            "당신은 AI STOCK의 뉴스 브리핑 비서입니다. 인사말은 이미 별도로 붙으니 절대 쓰지 말고, "
                    + "곧바로 본론(오늘의 시황 요약)부터 시작합니다. 주어진 기사 목록만 근거로 오늘의 "
                    + "시황을 3~4문장으로 담백하게 요약합니다. 기사에 없는 내용은 추측해서 덧붙이지 "
                    + "않고, 과장된 감탄사나 광고성 말투 없이 사실 위주로 전달합니다.";

    private static final Path OUTPUT_PATH = Path.of(System.getProperty("java.io.tmpdir"),
            "ai-stock-live-gemini-check", "outlet-briefing-board.json");

    private record OutletBoardEntry(String outlet, String domain, int articleCount, String content,
                                     List<SourceEntry> sources) {
    }

    private record SourceEntry(String title, String link) {
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_LIVE_GEMINI_TEST", matches = "true")
    void buildOutletBriefingBoard() throws IOException {
        NaverNewsApiClient naverNewsApiClient = new NaverNewsApiClient(RestClient.builder());
        ReflectionTestUtils.setField(naverNewsApiClient, "clientId", System.getenv("NAVER_CLIENT_ID"));
        ReflectionTestUtils.setField(naverNewsApiClient, "clientSecret", System.getenv("NAVER_CLIENT_SECRET"));
        ReflectionTestUtils.setField(naverNewsApiClient, "apiUrl", "https://naverapihub.apigw.ntruss.com/search/v1/news");

        GeminiApiClient geminiApiClient = new GeminiApiClient(RestClient.builder());
        ReflectionTestUtils.setField(geminiApiClient, "apiKey", System.getenv("GEMINI_API_KEY"));
        ReflectionTestUtils.setField(geminiApiClient, "judgeApiUrl",
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-lite:generateContent");
        ReflectionTestUtils.setField(geminiApiClient, "answerApiUrl",
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-lite:generateContent");

        List<OutletBoardEntry> board = new ArrayList<>();

        for (Map.Entry<String, String> entry : NewsRelevanceMatcher.OUTLET_NAMES.entrySet()) {
            String domain = entry.getKey();
            String outletName = entry.getValue();

            NaverNewsSearchResponse newsResponse = naverNewsApiClient.searchByOutlet(domain);
            System.out.println("=== " + outletName + " (" + domain + ") - " + newsResponse.results().size() + "건 ===");

            if (newsResponse.results().isEmpty()) {
                board.add(new OutletBoardEntry(outletName, domain, 0, null, List.of()));
                continue;
            }

            String content = BRIEFING_GREETING + summarize(geminiApiClient, newsResponse, outletName);
            List<SourceEntry> sources = newsResponse.results().stream()
                    .map(r -> new SourceEntry(r.title(), r.link()))
                    .toList();
            board.add(new OutletBoardEntry(outletName, domain, newsResponse.results().size(), content, sources));
            System.out.println(content);
        }

        Files.createDirectories(OUTPUT_PATH.getParent());
        ObjectMapper objectMapper = new ObjectMapper();
        Files.writeString(OUTPUT_PATH, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(board),
                StandardCharsets.UTF_8);
        System.out.println("=== 저장 위치: " + OUTPUT_PATH + " ===");
    }

    private String summarize(GeminiApiClient geminiApiClient, NaverNewsSearchResponse newsResponse, String outletName) {
        StringBuilder articles = new StringBuilder();
        for (NaverNewsSearchResponse.NaverNewsResult result : newsResponse.results()) {
            articles.append("- ").append(result.title());
            if (result.description() != null && !result.description().isBlank()) {
                articles.append(" : ").append(result.description());
            }
            articles.append('\n');
        }

        String prompt = outletName + "의 오늘자 주요 시황 기사입니다. 이 기사들을 종합해서 오늘의 시황을 요약해 주세요:\n" + articles;

        GeminiRequest request = new GeminiRequest(
                SUMMARY_SYSTEM_INSTRUCTION,
                prompt,
                List.of(),
                List.of(),
                List.of(),
                GeminiRequest.GeminiModel.ANSWER);
        GeminiResponse response = geminiApiClient.generate(request);
        return response.content();
    }
}
