package com.teamfp.aistock.infra.gemini;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withTooManyRequests;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;
import com.teamfp.aistock.infra.gemini.dto.GeminiRequest;
import com.teamfp.aistock.infra.gemini.dto.GeminiResponse;

/**
 * GeminiApiClient 단위 테스트 — Gemini 429(RESOURCE_EXHAUSTED) 재시도와 에러 코드 구분을 검증한다.
 * 재시도 대기 시간은 0으로 바꿔 테스트가 실제로 1초·2초를 기다리지 않게 한다.
 */
class GeminiApiClientTest {

    private static final String JUDGE_API_URL = "https://test-gemini/judge:generateContent";
    private static final String REQUEST_URL = JUDGE_API_URL + "?key=test-key";
    private static final String SUCCESS_BODY = """
            {"candidates":[{"content":{"parts":[{"text":"답변"}]}}],"usageMetadata":{"candidatesTokenCount":5}}
            """;
    private static final String RESOURCE_EXHAUSTED_BODY = """
            {"error":{"code":429,"message":"Resource has been exhausted","status":"RESOURCE_EXHAUSTED"}}
            """;

    private MockRestServiceServer mockServer;
    private GeminiApiClient geminiApiClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();

        geminiApiClient = new GeminiApiClient(builder);
        ReflectionTestUtils.setField(geminiApiClient, "apiKey", "test-key");
        ReflectionTestUtils.setField(geminiApiClient, "judgeApiUrl", JUDGE_API_URL);
        ReflectionTestUtils.setField(geminiApiClient, "answerApiUrl", "https://test-gemini/answer:generateContent");
        geminiApiClient.rateLimitRetryDelaysMillis = new long[] {0L, 0L};
    }

    private GeminiRequest judgeRequest() {
        return new GeminiRequest(null, "질문", List.of(), List.of(), List.of(), GeminiRequest.GeminiModel.JUDGE);
    }

    @Test
    @DisplayName("429가 한 번 나도 재시도로 성공하면 정상 응답을 돌려준다")
    void rateLimitedOnce_retriesAndSucceeds() {
        mockServer.expect(requestTo(REQUEST_URL))
                .andRespond(withTooManyRequests().body(RESOURCE_EXHAUSTED_BODY).contentType(MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo(REQUEST_URL))
                .andRespond(withSuccess(SUCCESS_BODY, MediaType.APPLICATION_JSON));

        GeminiResponse response = geminiApiClient.generate(judgeRequest());

        assertThat(response.content()).isEqualTo("답변");
        mockServer.verify();
    }

    @Test
    @DisplayName("429가 재시도(2회) 후에도 계속되면 총 3번 호출하고 GEMINI_RATE_LIMIT_EXCEEDED를 던진다")
    void rateLimitedPersistently_throwsGeminiRateLimitExceeded() {
        mockServer.expect(times(3), requestTo(REQUEST_URL))
                .andRespond(withTooManyRequests().body(RESOURCE_EXHAUSTED_BODY).contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> geminiApiClient.generate(judgeRequest()))
                .isInstanceOf(CustomException.class)
                .hasMessage(GeminiApiClient.RATE_LIMIT_MESSAGE)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.GEMINI_RATE_LIMIT_EXCEEDED);
        mockServer.verify();
    }

    @Test
    @DisplayName("상태 코드가 429가 아니어도 바디에 RESOURCE_EXHAUSTED가 있으면 할당량 초과로 본다")
    void resourceExhaustedBody_treatedAsRateLimit() {
        mockServer.expect(times(3), requestTo(REQUEST_URL))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(RESOURCE_EXHAUSTED_BODY).contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> geminiApiClient.generate(judgeRequest()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.GEMINI_RATE_LIMIT_EXCEEDED);
    }

    @Test
    @DisplayName("429가 아닌 오류는 재시도하지 않고 기존처럼 EXTERNAL_API_ERROR를 던진다")
    void otherError_noRetry_throwsExternalApiError() {
        mockServer.expect(times(1), requestTo(REQUEST_URL))
                .andRespond(withBadRequest().body("{\"error\":{\"status\":\"INVALID_ARGUMENT\"}}")
                        .contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> geminiApiClient.generate(judgeRequest()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.EXTERNAL_API_ERROR);
        mockServer.verify();
    }
}
