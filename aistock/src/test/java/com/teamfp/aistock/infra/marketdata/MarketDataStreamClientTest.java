package com.teamfp.aistock.infra.marketdata;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MarketDataStreamClient 검증(실시세 전환 7단계) — 생성기 GET /stream(SSE)을 흉내 낸 로컬 HTTP 서버로
 * data 줄만 묶음으로 넘기는지, 주석(heartbeat)·깨진 줄은 건너뛰는지, 주소를 설정에서 어떻게 정하는지 확인한다.
 */
class MarketDataStreamClientTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String startServer(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/stream", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(status, 0);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/stream";
    }

    @Test
    @DisplayName("data 줄의 JSON 배열만 묶음으로 넘기고, heartbeat 주석과 깨진 JSON은 건너뛴 뒤 연결이 끝나면 돌아온다")
    void consume_PassesDataLinesOnly() throws Exception {
        String url = startServer(200, """
                : connected

                data: [{"stockCode":"005930","currentPrice":253000,"tick":true}]

                : ping

                data: {broken

                data: [{"stockCode":"000660","currentPrice":500000,"hoga":true}]

                """);
        MarketDataStreamClient client = new MarketDataStreamClient();
        ReflectionTestUtils.setField(client, "streamUrl", url);
        List<JsonNode> received = new CopyOnWriteArrayList<>();

        client.consume(received::add);

        assertThat(received).hasSize(2);
        assertThat(received.get(0).get(0).path("stockCode").asText()).isEqualTo("005930");
        assertThat(received.get(1).get(0).path("hoga").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("200이 아니면 IOException")
    void consume_NonOk_Throws() throws Exception {
        String url = startServer(503, "");
        MarketDataStreamClient client = new MarketDataStreamClient();
        ReflectionTestUtils.setField(client, "streamUrl", url);

        assertThatThrownBy(() -> client.consume(events -> { })).isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("주소: stream-url → market-data.url의 /market-data를 /stream으로 → 로컬 기본값 순서로 정한다")
    void resolveStreamUrl() {
        MarketDataStreamClient client = new MarketDataStreamClient();
        assertThat(client.resolveStreamUrl()).isEqualTo("http://localhost:8081/stream");

        ReflectionTestUtils.setField(client, "marketDataUrl", "http://gen.internal:8081/market-data");
        assertThat(client.resolveStreamUrl()).isEqualTo("http://gen.internal:8081/stream");

        ReflectionTestUtils.setField(client, "streamUrl", "http://other:9000/stream");
        assertThat(client.resolveStreamUrl()).isEqualTo("http://other:9000/stream");
    }
}
