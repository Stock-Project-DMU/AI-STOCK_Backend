package com.teamfp.aistock.infra.gemini;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;
import com.teamfp.aistock.global.util.ExternalApiInvoker;
import com.teamfp.aistock.infra.gemini.dto.GeminiRequest;
import com.teamfp.aistock.infra.gemini.dto.GeminiRequest.FunctionExchange;
import com.teamfp.aistock.infra.gemini.dto.GeminiRequest.ToolDeclaration;
import com.teamfp.aistock.infra.gemini.dto.GeminiResponse;

import lombok.extern.slf4j.Slf4j;

/**
 * Google Gemini generateContent API 호출 담당.
 * app.gemini.api-key가 비어있으면(로컬에서 아직 키를 발급받기 전) Google 쪽에서 401을
 * 반환하고, 그 경우도 catch 블록에서 동일하게 EXTERNAL_API_ERROR로 변환된다 — 즉 키만
 * 나중에 채워 넣으면 별도 코드 수정 없이 그대로 동작한다.
 *
 * GeminiRequest.tools가 있으면 요청에 "함수 호출(Function Calling)" 도구 목록을 함께 실어
 * 보낸다. Gemini는 이 도구들의 name/description/parameters를 읽고, 사용자 질문에 도구가
 * 필요한지 스스로 판단해서 functionCall(응답, 여러 개 동시 요청 가능) 또는 일반 텍스트(응답)
 * 중 하나를 돌려준다. functionExchangeRounds가 있으면(도구를 실행한 결과를 되돌려주는 호출)
 * 원래 prompt/history로 만든 대화 뒤에 "모델의 함수호출 턴(들) + 함수실행결과 턴(들)"을
 * 실행 순서대로 이어붙여 보낸다.
 *
 * <p>하이브리드 모델 구성(2026-08-06, {@link GeminiRequest.GeminiModel} 참고): 처음엔
 * judge=gemini-2.5-flash-lite/answer=gemini-2.5-flash로 정했으나, 이 프로젝트 계정으로는
 * 2.5 세대 전체가 "신규 사용자에게 더 이상 제공되지 않음"(404)으로 막혀 있어 실제 호출이
 * 안 됐다(실 API 키로 직접 확인). 그다음 시도한 "-latest" 별칭(gemini-flash-lite-latest/
 * gemini-flash-latest)은 실제로는 gemini-3.6-flash로 서빙되어 원래 계획보다 3~6배 비쌌다.
 * 최종적으로 judge/answer 둘 다 gemini-3.1-flash-lite(실제 호출 가능 + 가장 저렴한 조합, 라이브
 * 3턴으로 톤·맥락유지·정확도 확인)로 통일했다. 아래 두 규격은 원래 gemini-flash-latest
 * (gemini-3.6-flash) 기준으로 검증됐던 것인데, gemini-3.1-flash-lite로도 동일하게 필요함을
 * 라이브 호출로 재확인했다 — 3.x 계열은 세대 전체가 thinking 모델인 것으로 보인다.</p>
 * (1) functionResponse 파트는 role="function"이 아니라 role="user"로 보내야 한다(공식 문서의
 *     예시와 달리 이 모델 버전은 role="function"을 거부한다 — "Role 'function' is not
 *     supported" 400 에러로 직접 확인).
 * (2) 모델의 functionCall 턴을 재구성할 때 원래 응답에 온 thoughtSignature를 그대로 함께
 *     보내지 않으면 요청 자체가 400으로 거부된다("thinking" 모델이라 사고 과정 서명이 필요).
 */
@Slf4j
@Component
public class GeminiApiClient {

    // Gemini 429 응답에 대한 사용자 안내 문구 — ErrorCode.GEMINI_RATE_LIMIT_EXCEEDED의 기본 메시지는 시뮬레이션
    // 자체 호출 제한용이라, Gemini 쪽 할당량 초과는 이 문구로 따로 안내한다.
    public static final String RATE_LIMIT_MESSAGE = "AI 요청이 일시적으로 많습니다. 잠시 후 다시 시도해 주세요.";
    // 동시 호출 허가를 기다리다 대기 상한(또는 요청 마감 시각)을 넘긴 경우의 안내 문구.
    public static final String CONCURRENCY_LIMIT_MESSAGE = "AI 요청이 많아 잠시 후 다시 시도해 주세요.";

    private final RestClient restClient;

    // Gemini 동시 호출 허가(app.gemini.semaphore.permits, 기본 10). 할당량은 프로젝트 단위라 재무설계·시뮬레이터·
    // 뉴스·브리핑이 이 세마포어 하나를 함께 쓴다. 실측 평균 약 2초/건 기준 P=10 — 11/01 부하 테스트(재무설계 20 +
    // 시뮬레이터 20 VU)에서 6이면 분당 약 180건에 묶여 대기 초과가 났고, 10이면 분당 약 400건을 Gemini 429 없이 처리했다. 공정 모드라 먼저 기다린 호출이
    // 먼저 허가를 받는다 — 재무설계처럼 한 요청이 Gemini를 여러 번 부를 때 뒤로 계속 밀리지 않게 한다.
    // HTTP 요청 1번마다 얻고 놓으며, 429 재시도 대기 중에는 놓는다(postWithRateLimitRetry()).
    final Semaphore geminiPermits;

    // 허가를 기다리는 최대 시간(20초). 단위 테스트에서 짧게 바꾸도록 final로 두지 않는다.
    long permitWaitMillis = 20_000L;

    // 429 재시도 대기 시간(지수 백오프: 1초 → 2초). 단위 테스트에서 대기 없이 돌리도록 final로 두지 않는다.
    long[] rateLimitRetryDelaysMillis = {1000L, 2000L};

    @Value("${app.gemini.api-key}")
    private String apiKey;

    // 하이브리드 모델 구성(2026-08-06) — 단순 작업(도구 필요 여부 판단, 잡담·되묻기)은
    // judge-api-url(gemini-2.5-flash-lite), 여러 도구 결과+포트폴리오+투자성향을 종합하는 최종
    // 답변은 answer-api-url(gemini-2.5-flash)을 쓴다. GeminiRequest.model()이 어느 쪽인지
    // 고른다.
    @Value("${app.gemini.judge-api-url}")
    private String judgeApiUrl;

    @Value("${app.gemini.answer-api-url}")
    private String answerApiUrl;

    public GeminiApiClient(RestClient.Builder restClientBuilder,
                           @Value("${app.gemini.semaphore.permits:10}") int semaphorePermits) {
        this.restClient = restClientBuilder.build();
        this.geminiPermits = new Semaphore(semaphorePermits, true);
    }

    public GeminiResponse generate(GeminiRequest request) {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        if (request.systemInstruction() != null && !request.systemInstruction().isBlank()) {
            requestBody.put("systemInstruction",
                    Map.of("parts", List.of(Map.of("text", request.systemInstruction()))));
        }
        requestBody.put("contents", buildContents(request));
        if (request.tools() != null && !request.tools().isEmpty()) {
            requestBody.put("tools", List.of(Map.of("functionDeclarations", toFunctionDeclarations(request.tools()))));
        }

        String apiUrl = request.model() == GeminiRequest.GeminiModel.ANSWER ? answerApiUrl : judgeApiUrl;
        // 429(할당량 초과)는 postWithRateLimitRetry()가 재시도 후 GEMINI_RATE_LIMIT_EXCEEDED(CustomException)로
        // 바꿔 던진다 — CustomException은 RestClientException이 아니라 ExternalApiInvoker를 그대로 통과하고,
        // 그 밖의 RestClientException만 기존처럼 EXTERNAL_API_ERROR(502)로 변환된다.
        GeminiApiResponse response = ExternalApiInvoker.call(
                () -> postWithRateLimitRetry(apiUrl, requestBody, request.deadline()),
                "Gemini API 호출 실패");

        return toGeminiResponse(response);
    }

    /**
     * Gemini가 429(RESOURCE_EXHAUSTED — 분당 요청·토큰 할당량 초과)를 돌려주면 1초, 2초 간격으로 최대 2번
     * 다시 보낸다. 분당 할당량은 몇 초 안에 풀리는 경우가 많아 짧은 재시도로 대부분 회복되고, 그래도 안 되면
     * "외부 API 연동 에러"(502) 대신 "요청이 많으니 잠시 후 다시"(429)로 안내해 사용자가 원인을 알 수 있게 한다.
     *
     * HTTP 요청 1번마다 동시 호출 허가(geminiPermits)를 얻고, 응답을 받으면 바로 놓는다. 재시도 대기 동안에는
     * 허가를 놓아 두었다가 다시 얻는다 — 쉬는 동안 허가를 쥐고 있으면 다른 요청이 그만큼 더 기다린다.
     * 응답 파싱(toGeminiResponse)은 generate()에서 허가 바깥에서 한다.
     */
    private GeminiApiResponse postWithRateLimitRetry(String apiUrl, Map<String, Object> requestBody, Instant deadline) {
        for (int attempt = 0; ; attempt++) {
            acquirePermit(deadline);
            try {
                return restClient.post()
                        .uri(apiUrl + "?key={apiKey}", apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(requestBody)
                        .retrieve()
                        .body(GeminiApiResponse.class);
            } catch (RestClientResponseException e) {
                if (!isRateLimited(e)) {
                    throw e;
                }
                if (attempt >= rateLimitRetryDelaysMillis.length) {
                    log.warn("Gemini API 할당량 초과(429)가 재시도 {}회 후에도 계속됨", rateLimitRetryDelaysMillis.length);
                    throw new CustomException(ErrorCode.GEMINI_RATE_LIMIT_EXCEEDED, RATE_LIMIT_MESSAGE);
                }
                log.warn("Gemini API 할당량 초과(429), {}ms 후 재시도 {}/{}회",
                        rateLimitRetryDelaysMillis[attempt], attempt + 1, rateLimitRetryDelaysMillis.length);
            } finally {
                geminiPermits.release();
            }
            sleepBeforeRetry(rateLimitRetryDelaysMillis[attempt]);
        }
    }

    // 동시 호출 허가를 최대 permitWaitMillis(20초)까지 기다린다. 마감 시각이 있으면 그때까지만 기다리고, 이미
    // 지났으면 기다리지 않고 바로 끝낸다 — 결과를 받을 쪽이 없는 호출로 허가와 할당량을 쓰지 않기 위함이다.
    private void acquirePermit(Instant deadline) {
        long waitMillis = permitWaitMillis;
        if (deadline != null) {
            long remainingMillis = Duration.between(Instant.now(), deadline).toMillis();
            if (remainingMillis <= 0) {
                log.warn("요청 마감 시각이 지나 Gemini 호출을 하지 않음 - deadline: {}", deadline);
                throw new CustomException(ErrorCode.GEMINI_RATE_LIMIT_EXCEEDED, CONCURRENCY_LIMIT_MESSAGE);
            }
            waitMillis = Math.min(waitMillis, remainingMillis);
        }
        boolean acquired;
        try {
            acquired = geminiPermits.tryAcquire(waitMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CustomException(ErrorCode.GEMINI_RATE_LIMIT_EXCEEDED, CONCURRENCY_LIMIT_MESSAGE);
        }
        if (!acquired) {
            log.warn("Gemini 동시 호출 허가를 {}ms 안에 받지 못함 - 대기 중 {}건", waitMillis, geminiPermits.getQueueLength());
            throw new CustomException(ErrorCode.GEMINI_RATE_LIMIT_EXCEEDED, CONCURRENCY_LIMIT_MESSAGE);
        }
    }

    // 상태 코드가 429이거나, 프록시 등을 거쳐 상태 코드가 바뀌었더라도 Gemini 에러 바디의 status가
    // RESOURCE_EXHAUSTED면 할당량 초과로 본다.
    private boolean isRateLimited(RestClientResponseException e) {
        return e.getStatusCode().value() == HttpStatus.TOO_MANY_REQUESTS.value()
                || e.getResponseBodyAsString().contains("RESOURCE_EXHAUSTED");
    }

    // 단위 테스트에서 대기 중 허가 수를 확인하도록 private으로 두지 않는다.
    void sleepBeforeRetry(long delayMillis) {
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException e) {
            // 요청 마감 시간(AiPlanningService) 초과 등으로 중단된 경우 — 더 기다리지 않고 바로 끝낸다.
            Thread.currentThread().interrupt();
            throw new CustomException(ErrorCode.GEMINI_RATE_LIMIT_EXCEEDED, RATE_LIMIT_MESSAGE);
        }
    }

    private List<Map<String, Object>> buildContents(GeminiRequest request) {
        List<Map<String, Object>> contents = new ArrayList<>();
        for (GeminiRequest.HistoryTurn turn : request.history()) {
            contents.add(toContentPart(turn.role(), turn.content()));
        }
        contents.add(toContentPart("user", request.prompt()));

        // 라운드 하나 = 실제로 Gemini가 한 응답에서 동시에 요청했던 functionCall들의 묶음이므로,
        // 재구성할 때도 그 라운드를 "model 턴 하나(파트 여러 개) + user 턴 하나(파트 여러 개)"로
        // 묶어 보낸다 — 라운드를 쪼개 model 턴을 여러 개로 나누면 실제 있었던 대화 구조와
        // 달라진다(클래스 상단 GeminiRequest javadoc 참고).
        for (List<FunctionExchange> round : request.functionExchangeRounds()) {
            List<Map<String, Object>> callParts = new ArrayList<>();
            List<Map<String, Object>> responseParts = new ArrayList<>();

            for (FunctionExchange exchange : round) {
                // Gemini가 스키마를 어기고 args 자체를 비워(null) 보내는 경우가 실제로 있다
                // (AiPlanningService.stringArg()도 이를 방어함) — Map.of는 값이 null이면 그
                // 자리에서 NPE를 던지므로, 재전송 시에는 빈 맵으로 대체한다.
                Map<String, Object> functionCallPart = new LinkedHashMap<>();
                functionCallPart.put("functionCall", Map.of(
                        "name", exchange.functionName(),
                        "args", exchange.args() != null ? exchange.args() : Map.of()));
                if (exchange.thoughtSignature() != null) {
                    functionCallPart.put("thoughtSignature", exchange.thoughtSignature());
                }
                callParts.add(functionCallPart);

                // 실제 API 검증 결과 functionResponse 파트는 role="function"이 아니라
                // role="user"로 보내야 한다 — 클래스 상단 javadoc 참고.
                responseParts.add(Map.of("functionResponse",
                        Map.of("name", exchange.functionName(), "response", exchange.functionResult())));
            }

            contents.add(Map.of("role", "model", "parts", callParts));
            contents.add(Map.of("role", "user", "parts", responseParts));
        }

        return contents;
    }

    private List<Map<String, Object>> toFunctionDeclarations(List<ToolDeclaration> tools) {
        return tools.stream().map(tool -> {
            Map<String, Object> properties = new LinkedHashMap<>();
            tool.parameters().forEach((paramName, spec) ->
                    properties.put(paramName, Map.of("type", spec.type(), "description", spec.description())));

            Map<String, Object> parametersSchema = Map.of(
                    "type", "object",
                    "properties", properties,
                    "required", tool.required());

            return Map.<String, Object>of(
                    "name", tool.name(),
                    "description", tool.description(),
                    "parameters", parametersSchema);
        }).toList();
    }

    private Map<String, Object> toContentPart(String role, String text) {
        return Map.of("role", role, "parts", List.of(Map.of("text", text)));
    }

    private GeminiResponse toGeminiResponse(GeminiApiResponse response) {
        if (response == null || response.candidates() == null || response.candidates().isEmpty()) {
            log.error("Gemini API 응답에 candidates가 없음 - response: {}", response);
            throw new CustomException(ErrorCode.EXTERNAL_API_ERROR);
        }

        // 세이프티 필터에 걸리거나(finishReason=SAFETY) 토큰 한도 초과로 잘린 경우(MAX_TOKENS)
        // candidate는 있지만 content/parts가 비어있을 수 있다 — 그대로 get(0)하면 NPE로
        // 터져 GlobalExceptionHandler의 500 catch-all로 빠지므로, 여기서 명확히 502로 구분한다.
        GeminiApiResponse.Candidate candidate = response.candidates().get(0);
        if (candidate.content() == null
                || candidate.content().parts() == null
                || candidate.content().parts().isEmpty()) {
            log.error("Gemini API 응답에 답변 텍스트가 없음(세이프티 필터/토큰 초과 등) - response: {}", response);
            throw new CustomException(ErrorCode.EXTERNAL_API_ERROR);
        }

        // schema.sql: ai_planning_messages.prompt_tokens는 "Gemini 토큰 사용량 (AI 응답만)"으로
        // 정의되어 있다. totalTokenCount는 prompt+응답 합산값이라 이 컬럼 의미와 맞지 않으므로,
        // 응답 전용 값인 candidatesTokenCount를 사용한다.
        Integer tokenCount = response.usageMetadata() != null
                ? response.usageMetadata().candidatesTokenCount()
                : null;

        List<GeminiApiResponse.Part> parts = candidate.content().parts();

        // Gemini는 한 응답 안에서 여러 도구를 동시에 요청할 수 있다(parallel function calling,
        // 실제 API로 확인됨) — parts 전체를 훑어 functionCall이 있는 파트를 모두 모은다.
        List<GeminiResponse.FunctionCall> functionCalls = parts.stream()
                .filter(part -> part.functionCall() != null)
                .map(part -> new GeminiResponse.FunctionCall(
                        part.functionCall().name(), part.functionCall().args(), part.thoughtSignature()))
                .toList();

        // 텍스트가 있는 파트를 parts 전체에서 찾는다(첫 번째 파트라고 단정하지 않는다) — Gemini가
        // functionCall과 함께 안내 텍스트를 같이 보낼 수도 있고("확인해볼게요" 등), thinking 모델
        // 특성상 텍스트가 첫 파트가 아닌 순서로 올 가능성도 배제하지 않는다.
        String text = parts.stream()
                .map(GeminiApiResponse.Part::text)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);

        if (!functionCalls.isEmpty()) {
            // functionCall과 함께 온 안내 텍스트가 있으면 버리지 않고 같이 담아 되돌려준다.
            return new GeminiResponse(text, tokenCount, functionCalls);
        }

        if (text == null) {
            log.error("Gemini API 응답에 텍스트도 함수호출도 없음 - response: {}", response);
            throw new CustomException(ErrorCode.EXTERNAL_API_ERROR);
        }

        return new GeminiResponse(text, tokenCount, List.of());
    }

    // Gemini generateContent API 원본 응답 구조. 요청 바디는 히스토리 길이가 매번 달라 Map으로
    // 직접 구성하지만, 응답은 고정된 구조이므로 record로 타입 안정적으로 파싱한다.
    private record GeminiApiResponse(List<Candidate> candidates, UsageMetadata usageMetadata) {

        private record Candidate(Content content) {
        }

        private record Content(List<Part> parts) {
        }

        // text와 functionCall은 상호 배타적이다 — Gemini가 도구 호출을 결정하면 functionCall만,
        // 일반 답변이면 text만 채워져 온다. thoughtSignature는 functionCall이 있을 때만 오며,
        // 다음 요청에서 그 functionCall 턴을 재구성할 때 그대로 echo해야 한다(클래스 상단 참고).
        private record Part(String text, FunctionCallPart functionCall, String thoughtSignature) {
        }

        private record FunctionCallPart(String name, Map<String, Object> args) {
        }

        private record UsageMetadata(Integer promptTokenCount, Integer candidatesTokenCount, Integer totalTokenCount) {
        }
    }
}
