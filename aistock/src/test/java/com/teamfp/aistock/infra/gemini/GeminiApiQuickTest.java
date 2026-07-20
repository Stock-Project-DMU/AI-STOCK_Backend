package com.teamfp.aistock.infra.gemini;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gemini API를 실제로 호출해 "AI 재무설계사" 페르소나 응답이 정상적으로 오는지
 * 확인해보기 위한 독립 실행용 테스트. 아직 GeminiApiClient가 구현되기 전이라
 * infra/domain 구조와 무관하게 REST 호출만 단독으로 검증한다.
 * GEMINI_API_KEY 환경변수가 없으면 자동으로 스킵된다.
 */
class GeminiApiQuickTest {

    private static final String MODEL = System.getenv().getOrDefault("GEMINI_MODEL", "gemini-2.0-flash");
    private static final String ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/" + MODEL + ":generateContent";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private record GeminiHttpResponse(List<Candidate> candidates) {}
    private record Candidate(Content content) {}
    private record Content(List<Part> parts) {}
    private record Part(String text) {}

    @Test
    @EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
    void aiFinancialPlanner_RespondsToUserQuestion() throws Exception {
        String apiKey = System.getenv("GEMINI_API_KEY");

        String systemPrompt = "너는 'AI STOCK' 서비스의 AI 재무설계사야. "
                + "사용자의 재무 상황을 듣고 현실적이고 구체적인 조언을 한국어로 간결하게 제공해.";
        String userQuestion = "월급 300만원인데 3년 안에 5000만원을 모으고 싶어. 어떻게 해야 할까?";

        Map<String, Object> requestBody = Map.of(
                "systemInstruction", Map.of(
                        "parts", List.of(Map.of("text", systemPrompt))
                ),
                "contents", List.of(
                        Map.of(
                                "role", "user",
                                "parts", List.of(Map.of("text", userQuestion))
                        )
                )
        );
        String requestJson = objectMapper.writeValueAsString(requestBody);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ENDPOINT))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode())
                .as("Gemini API 응답 본문: %s", response.body())
                .isEqualTo(200);

        GeminiHttpResponse parsed = objectMapper.readValue(response.body(), GeminiHttpResponse.class);
        String answer = parsed.candidates().get(0).content().parts().get(0).text();

        System.out.println("========== [질문] " + userQuestion + " ==========");
        System.out.println("========== AI 재무설계사 응답 ==========");
        System.out.println(answer);
        System.out.println("========================================");

        assertThat(answer).isNotBlank();
    }
}
