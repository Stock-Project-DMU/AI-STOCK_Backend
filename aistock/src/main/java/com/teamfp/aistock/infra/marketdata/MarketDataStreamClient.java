package com.teamfp.aistock.infra.marketdata;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * 생성기 실시간 시세 스트림 클라이언트(실시세 전환 7단계) — local-market-data-generator의 {@code GET /stream}(SSE)을 열어
 * 바뀐 종목의 체결·호가 묶음(JSON 배열, 0.1초 단위)을 받는다.
 *
 * <p>{@code market_data.json} 저장(1초)·폴링(1초)을 거치지 않아 화면 반영이 빨라진다. 연결 방향은
 * {@link LocalMarketDataReader}의 URL 모드와 같다(백엔드 → 생성기). 받은 묶음을 어떻게 보낼지는 구독 종목을 아는 domain
 * 쪽({@code MarketDataStreamSubscriber})이 정한다 — infra는 domain을 참조하지 않는다.</p>
 */
@Slf4j
@Component
public class MarketDataStreamClient {

    private static final String DEFAULT_STREAM_URL = "http://localhost:8081/stream";
    private static final String MARKET_DATA_PATH = "/market-data";
    private static final String STREAM_PATH = "/stream";
    private static final String DATA_PREFIX = "data: ";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    // 응답 헤더를 기다리는 상한 — 연결은 됐는데 생성기가 응답을 시작하지 않으면 끊고 다시 연결한다(2026-10-12 코드리뷰).
    // 스트림 본문(이벤트)은 이 제한을 받지 않는다(헤더를 받으면 send()가 돌아온다).
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // 비어 있으면 market-data.url에서 만든다(배포) — 그것도 없으면 로컬 생성기 기본 주소.
    @Value("${market-data.stream-url:}")
    private String streamUrl = "";

    @Value("${market-data.url:}")
    private String marketDataUrl = "";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    // 지금 열려 있는 스트림 본문 — closeIfIdle()이 다른 스레드에서 닫을 수 있게 둔다.
    private final AtomicReference<Stream<String>> openStream = new AtomicReference<>();
    private volatile long lastReceivedAtMillis;

    /**
     * 스트림을 열고 묶음이 올 때마다 {@code onEvents}를 부른다. 연결이 끊기거나 닫힐 때까지 돌아오지 않는다.
     * 연결 실패·비정상 응답은 {@link IOException}. 한 묶음의 처리 실패는 로그만 남기고 다음 묶음을 계속 받는다.
     */
    public void consume(Consumer<JsonNode> onEvents) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(resolveStreamUrl()))
                .header("Accept", "text/event-stream")
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
        HttpResponse<Stream<String>> response = httpClient.send(request, HttpResponse.BodyHandlers.ofLines());
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("시세 스트림 응답 코드 " + response.statusCode());
        }
        lastReceivedAtMillis = System.currentTimeMillis();
        try (Stream<String> lines = response.body()) {
            openStream.set(lines);
            Iterator<String> iterator = lines.iterator();
            while (iterator.hasNext()) {
                String line = iterator.next();
                lastReceivedAtMillis = System.currentTimeMillis();
                if (line.startsWith(DATA_PREFIX)) {
                    handle(line.substring(DATA_PREFIX.length()), onEvents);
                }
            }
        } finally {
            openStream.set(null);
        }
    }

    private void handle(String payload, Consumer<JsonNode> onEvents) {
        try {
            onEvents.accept(OBJECT_MAPPER.readTree(payload));
        } catch (JsonProcessingException e) {
            log.warn("시세 스트림 묶음 파싱 실패 - 사유: {}", e.getOriginalMessage());
        } catch (RuntimeException e) {
            log.warn("시세 스트림 묶음 처리 실패 - 사유: {}", e.getMessage());
        }
    }

    /** heartbeat(15초)도 {@code idleMillis} 동안 오지 않으면 끊긴 연결로 보고 닫는다 — {@link #consume}이 돌아오게 한다. */
    public void closeIfIdle(long idleMillis) {
        Stream<String> lines = openStream.get();
        if (lines != null && System.currentTimeMillis() - lastReceivedAtMillis > idleMillis) {
            log.warn("시세 스트림 응답 없음 {}ms — 연결을 닫고 다시 연결합니다", idleMillis);
            lines.close();
        }
    }

    public String resolveStreamUrl() {
        if (streamUrl != null && !streamUrl.isBlank()) {
            return streamUrl.trim();
        }
        if (marketDataUrl != null && !marketDataUrl.isBlank()) {
            String url = marketDataUrl.trim();
            return url.endsWith(MARKET_DATA_PATH)
                    ? url.substring(0, url.length() - MARKET_DATA_PATH.length()) + STREAM_PATH
                    : url.replaceAll("/+$", "") + STREAM_PATH;
        }
        return DEFAULT_STREAM_URL;
    }
}
