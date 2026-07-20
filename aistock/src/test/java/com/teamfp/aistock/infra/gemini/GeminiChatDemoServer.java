package com.teamfp.aistock.infra.gemini;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import tools.jackson.databind.ObjectMapper;

/**
 * Spring Boot 앱(DB 필요) 없이, Gemini API만 실제로 호출하는 "AI 재무설계사" 채팅 페이지를
 * 브라우저에서 직접 확인해보기 위한 초간단 데모 서버.
 * GeminiApiClient/컨트롤러가 아직 구현 전이라 정식 아키텍처와 무관하게 독립 실행한다.
 *
 * 실행: GEMINI_API_KEY 환경변수를 넣고 아래 테스트를 실행하면
 * http://localhost:8090 에서 채팅 UI가 뜨고, DEMO_DURATION_MINUTES(기본 20분) 동안 유지된다.
 */
class GeminiChatDemoServer {

    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("DEMO_PORT", "8090"));
    private static final int DURATION_MINUTES =
            Integer.parseInt(System.getenv().getOrDefault("DEMO_DURATION_MINUTES", "20"));
    private static final String MODEL = System.getenv().getOrDefault("GEMINI_MODEL", "gemini-flash-latest");
    private static final String GEMINI_ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/" + MODEL + ":generateContent";
    private static final String SYSTEM_PROMPT = """
            너는 'AI STOCK'의 전문 AI 재무설계사야. 실제 재무설계 상담사처럼 행동해야 하고,
            한두 마디 듣고 바로 일반론적인 답을 주는 Q&A 챗봇처럼 굴면 안 돼. 아래 절차를 지켜.

            1. 사용자가 재무 목표나 고민을 처음 꺼내면, 설계안을 바로 주지 말고 먼저 상담에 필요한
               정보를 질문으로 확인해. 최소한 다음 중 대화에서 아직 안 나온 항목을 물어봐:
               - 월 소득 / 월 고정지출(주거비, 생활비 등)
               - 현재 모아둔 자산과 부채(대출 등)
               - 목표 금액과 목표 기간
               - 투자 경험 유무, 손실을 감내할 수 있는 정도(투자 성향)
               - 이미 하고 있는 저축/투자 수단이 있는지
               한 번에 다 몰아서 묻지 말고 자연스럽게 2~4개씩 나눠서 물어봐.

            2. 위 정보가 충분히 모이면 그때 구체적인 재무설계안을 제시해:
               - 월 저축/투자 배분(금액과 비중)
               - 안정자산 vs 투자자산 포트폴리오 구성과 이유
               - 목표 달성까지 예상 기간과 계산 근거
               - 리스크(원금 손실 가능성 등)와 대비책

            3. 사용자가 이미 충분한 정보를 스스로 다 줬다면 굳이 다시 묻지 말고 바로 설계안으로 넘어가.

            4. 말투는 전문적이면서도 이해하기 쉽게, 한국어로. 투자 권유 시에는 원금 손실
               가능성이 있다는 점을 짚어줘. 이전 대화 맥락을 기억하고 상담을 이어가.
            """;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    // 데모 서버 하나가 세션 하나만 다루는 간단한 인메모리 대화 이력 (role: "user" | "model")
    private static final List<Map<String, String>> HISTORY = new CopyOnWriteArrayList<>();

    private record GeminiHttpResponse(List<Candidate> candidates) {}
    private record Candidate(Content content) {}
    private record Content(List<Part> parts) {}
    private record Part(String text) {}

    @Test
    @EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
    void runChatDemoServer() throws Exception {
        String apiKey = System.getenv("GEMINI_API_KEY");

        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/", new PageHandler());
        server.createContext("/api/chat", new ChatHandler(apiKey));
        server.start();

        System.out.println("==================================================");
        System.out.println("AI 재무설계사 채팅 데모 서버 실행 중");
        System.out.println("브라우저에서 열어서 확인: http://localhost:" + PORT);
        System.out.println(DURATION_MINUTES + "분 동안 유지되고 자동 종료됩니다.");
        System.out.println("==================================================");

        Thread.sleep(Duration.ofMinutes(DURATION_MINUTES).toMillis());

        server.stop(0);
        System.out.println("데모 서버 종료.");
    }

    private static class PageHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            byte[] body = CHAT_PAGE_HTML.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        }
    }

    private static class ChatHandler implements HttpHandler {
        private final String apiKey;

        ChatHandler(String apiKey) {
            this.apiKey = apiKey;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            Map<String, Object> requestPayload = OBJECT_MAPPER.readValue(exchange.getRequestBody(), Map.class);
            String userMessage = String.valueOf(requestPayload.get("message"));

            long startedAt = System.currentTimeMillis();
            String reply;
            try {
                reply = callGemini(userMessage);
                HISTORY.add(Map.of("role", "user", "text", userMessage));
                HISTORY.add(Map.of("role", "model", "text", reply));
            } catch (Exception e) {
                reply = "AI 응답 중 오류가 발생했습니다: " + e.getMessage();
            }
            System.out.println("[chat] " + (System.currentTimeMillis() - startedAt) + "ms 소요 - 질문: " + userMessage);

            byte[] body = OBJECT_MAPPER.writeValueAsString(Map.of("reply", reply)).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        }

        private String callGemini(String userMessage) throws Exception {
            List<Map<String, Object>> contents = new ArrayList<>();
            for (Map<String, String> turn : HISTORY) {
                contents.add(Map.of(
                        "role", turn.get("role"),
                        "parts", List.of(Map.of("text", turn.get("text")))
                ));
            }
            contents.add(Map.of(
                    "role", "user",
                    "parts", List.of(Map.of("text", userMessage))
            ));

            Map<String, Object> requestBody = Map.of(
                    "systemInstruction", Map.of("parts", List.of(Map.of("text", SYSTEM_PROMPT))),
                    "contents", contents
            );

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(GEMINI_ENDPOINT))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("x-goog-api-key", apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(requestBody)))
                    .build();

            HttpResponse<String> response = sendWithRetry(request);
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Gemini API 오류(" + response.statusCode() + "): " + response.body());
            }

            GeminiHttpResponse parsed = OBJECT_MAPPER.readValue(response.body(), GeminiHttpResponse.class);
            return parsed.candidates().get(0).content().parts().get(0).text();
        }

        // 모델 과부하(503)나 순간 429는 구글 서버 쪽 일시적 문제라 재시도하면 대부분 바로 풀린다.
        // 최대 2번, 1초/2초 대기 후 재시도하고 그래도 안 되면 마지막 응답을 그대로 반환한다.
        private HttpResponse<String> sendWithRetry(HttpRequest request) throws Exception {
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            for (int attempt = 1; attempt <= 2 && isRetryable(response.statusCode()); attempt++) {
                Thread.sleep(attempt * 1000L);
                response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            }
            return response;
        }

        private boolean isRetryable(int statusCode) {
            return statusCode == 503 || statusCode == 429;
        }
    }

    private static final String CHAT_PAGE_HTML = """
            <!DOCTYPE html>
            <html lang="ko">
            <head>
            <meta charset="UTF-8">
            <title>AI STOCK - AI 재무설계사 채팅 데모</title>
            <style>
              body { font-family: sans-serif; max-width: 640px; margin: 40px auto; padding: 0 16px; background: #f7f7f8; }
              h1 { font-size: 18px; }
              #log { border: 1px solid #ddd; border-radius: 8px; height: 480px; overflow-y: auto; padding: 12px; background: #fff; }
              .msg { margin: 8px 0; padding: 10px 14px; border-radius: 10px; max-width: 80%; white-space: pre-wrap; line-height: 1.4; }
              .user { background: #daf1ff; margin-left: auto; text-align: right; }
              .ai { background: #eee; margin-right: auto; }
              .row { display: flex; margin: 12px 0; }
              #msg { flex: 1; padding: 10px; border-radius: 6px; border: 1px solid #ccc; }
              button { padding: 10px 16px; margin-left: 8px; border: none; border-radius: 6px; background: #2563eb; color: #fff; cursor: pointer; }
              button:disabled { background: #9ca3af; }
            </style>
            </head>
            <body>
              <h1>AI STOCK · AI 재무설계사 채팅 데모</h1>
              <div id="log"></div>
              <div class="row">
                <input id="msg" type="text" placeholder="예: 월급 300만원인데 3년 안에 5000만원 모으고 싶어" />
                <button id="sendBtn" onclick="send()">전송</button>
              </div>
              <script>
                function appendMessage(role, text) {
                  const log = document.getElementById('log');
                  const div = document.createElement('div');
                  div.className = 'msg ' + (role === 'user' ? 'user' : 'ai');
                  div.textContent = text;
                  log.appendChild(div);
                  log.scrollTop = log.scrollHeight;
                }

                async function send() {
                  const input = document.getElementById('msg');
                  const btn = document.getElementById('sendBtn');
                  const text = input.value.trim();
                  if (!text) return;
                  appendMessage('user', text);
                  input.value = '';
                  btn.disabled = true;
                  try {
                    const res = await fetch('/api/chat', {
                      method: 'POST',
                      headers: { 'Content-Type': 'application/json' },
                      body: JSON.stringify({ message: text })
                    });
                    const data = await res.json();
                    appendMessage('ai', data.reply);
                  } catch (err) {
                    appendMessage('ai', '요청 실패: ' + err);
                  } finally {
                    btn.disabled = false;
                  }
                }

                document.getElementById('msg').addEventListener('keydown', function (e) {
                  if (e.key === 'Enter') send();
                });
              </script>
            </body>
            </html>
            """;
}
