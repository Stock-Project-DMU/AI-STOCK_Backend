package com.teamfp.aistock.infra.marketdata;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import com.sun.net.httpserver.HttpServer;
import com.teamfp.aistock.infra.marketdata.dto.CurrentPriceDetailDto;
import com.teamfp.aistock.infra.marketdata.dto.HogaData;

class LocalMarketDataReaderTest {

    private static final String STOCK_CODE = "005930";

    @TempDir
    private Path tempDir;

    private LocalMarketDataReader reader;

    @BeforeEach
    void setUp() {
        reader = new LocalMarketDataReader();
        ReflectionTestUtils.setField(reader, "localDataPath", tempDir.toString());
    }

    @Test
    @DisplayName("실시간 스트림 체결이 파일보다 새로우면 가격·등락·거래량만 덮어 쓰고, 파일이 더 새로워지면 파일 값을 쓴다")
    void applyStreamQuotes_overlaysNewerQuote() throws Exception {
        Files.writeString(tempDir.resolve("market_data.json"), """
                {"005930": {"stockCode":"005930","stockName":"삼성전자","currentPrice":252500,"changeAmount":-9500,
                   "changeRate":-3.62,"volume":9000000,"per":50.8,"updatedAt":"2026-10-12T13:20:00.000"}}""");
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

        // 더 이른 체결·tick=false 묶음은 무시
        reader.applyStreamQuotes(mapper.readTree("""
                [{"stockCode":"005930","currentPrice":250000,"volume":1,"updatedAt":"2026-10-12T13:19:59.000","tick":true},
                 {"stockCode":"005930","currentPrice":1,"volume":1,"updatedAt":"2026-10-12T13:30:00.000","tick":false}]"""));
        assertThat(reader.getCurrentPrice("005930")).get().satisfies(price -> assertThat(price.getCurrentPrice()).isEqualTo(252500L));

        reader.applyStreamQuotes(mapper.readTree("""
                [{"stockCode":"005930","currentPrice":253000,"changeAmount":-9000,"changeRate":-3.44,"volume":9100000,
                  "updatedAt":"2026-10-12T13:20:00.300","tick":true}]"""));
        assertThat(reader.getCurrentPrice("005930")).get().satisfies(price -> {
            assertThat(price.getCurrentPrice()).isEqualTo(253000L);
            assertThat(price.getVolume()).isEqualTo(9_100_000L);
            assertThat(price.getPer()).isEqualTo(50.8);              // 파일에만 있는 지표는 그대로
            assertThat(price.getStockName()).isEqualTo("삼성전자");
        });

        // updatedAt이 30초 넘게 뒤로 간 체결(생성기 재시작·시계 변경)은 다시 받는다 — 화면 전송과 같은 기준. 예전 시계의 실시간 값(253,000)이
        // 계속 우선하지 않고, 지금은 파일(13:20)이 더 새로우므로 파일 값(252,500)이 보인다.
        reader.applyStreamQuotes(mapper.readTree("""
                [{"stockCode":"005930","currentPrice":251000,"changeAmount":-11000,"changeRate":-4.2,"volume":9200000,
                  "updatedAt":"2026-10-12T13:15:00.000","tick":true}]"""));
        assertThat(reader.getCurrentPrice("005930")).get().satisfies(price -> assertThat(price.getCurrentPrice()).isEqualTo(252500L));

        // 스트림이 끊긴 뒤 파일이 더 새로워지면 파일 값으로 돌아간다
        Files.writeString(tempDir.resolve("market_data.json"), """
                {"005930": {"stockCode":"005930","stockName":"삼성전자","currentPrice":254000,"changeAmount":-8000,
                   "changeRate":-3.05,"volume":9200000,"per":50.8,"pbr":5.76,"updatedAt":"2026-10-12T13:20:05.000"}}""");
        assertThat(reader.getCurrentPrice("005930")).get().satisfies(price -> assertThat(price.getCurrentPrice()).isEqualTo(254000L));
    }

    @Test
    @DisplayName("호가 10단계와 매도·매수 총잔량을 읽는다(2026-10-12)")
    void getHoga_readsTenLevelsAndTotals() throws IOException {
        Files.writeString(tempDir.resolve("market_data.json"), """
                {"005930": {"stockCode":"005930","askPrices":[1,2,3,4,5,6,7,8,9,10],"askVolumes":[1,1,1,1,1,1,1,1,1,1],
                   "bidPrices":[10,9,8,7,6,5,4,3,2,1],"bidVolumes":[2,2,2,2,2,2,2,2,2,2],
                   "totalAskVolume":1000,"totalBidVolume":2000}}""");

        assertThat(reader.getHoga("005930")).get().satisfies(hoga -> {
            assertThat(hoga.getAskPrices()).hasSize(10);
            assertThat(hoga.getTotalAskVolume()).isEqualTo(1000L);
            assertThat(hoga.getTotalBidVolume()).isEqualTo(2000L);
        });
    }

    @Test
    @DisplayName("market_data.json에서 종목코드 키를 찾아 CurrentPriceDetailDto로 반환한다")
    void success_readsMarketDataFile() throws IOException {
        Files.writeString(tempDir.resolve("market_data.json"), """
                {
                  "005930": {"stockCode":"005930","stockName":"삼성전자","currentPrice":78500,
                    "changeAmount":500,"changeRate":0.64,"volume":12345678,
                    "per":15.2,"pbr":1.3,"high52w":85000,"high52wDate":"20260101",
                    "low52w":60000,"low52wDate":"20250601","listingShares":5969783,
                    "foreignExhaustionRate":51.2,"updatedAt":"2026-09-11T12:00:00"},
                  "000660": {"stockCode":"000660","stockName":"SK하이닉스","currentPrice":220000,
                    "changeAmount":-1000,"changeRate":-0.45,"volume":3456789}
                }""");

        Optional<CurrentPriceDetailDto> result = reader.getCurrentPrice(STOCK_CODE);

        assertThat(result).isPresent();
        assertThat(result.get().getStockName()).isEqualTo("삼성전자");
        assertThat(result.get().getCurrentPrice()).isEqualTo(78500L);
        assertThat(result.get().getPer()).isEqualTo(15.2);
        assertThat(result.get().getUpdatedAt()).isEqualTo(java.time.LocalDateTime.of(2026, 9, 11, 12, 0, 0));
    }

    @Test
    @DisplayName("ETF 종목의 exchgubun 필드를 CurrentPriceDetailDto로 그대로 읽는다")
    void success_readsExchgubunField() throws IOException {
        Files.writeString(tempDir.resolve("market_data.json"), """
                {
                  "069500": {"stockCode":"069500","stockName":"KODEX 200","currentPrice":98265,
                    "changeAmount":175,"changeRate":0.18,"volume":13128894,"etf":true,"exchgubun":"K"}
                }""");

        Optional<CurrentPriceDetailDto> result = reader.getCurrentPrice("069500");

        assertThat(result).isPresent();
        assertThat(result.get().getExchgubun()).isEqualTo("K");
    }

    @Test
    @DisplayName("같은 종목 JSON 객체에 호가 필드(askPrices 등)가 섞여 있어도 현재가 필드는 그대로 읽는다")
    void success_ignoresHogaFieldsMixedIntoSameObject() throws IOException {
        Files.writeString(tempDir.resolve("market_data.json"), """
                {"005930": {"stockCode":"005930","stockName":"삼성전자","currentPrice":78500,
                  "changeAmount":500,"changeRate":0.64,"volume":12345678,
                  "askPrices":[78600,78700,78800,78900,79000],"askVolumes":[100,200,300,400,500],
                  "bidPrices":[78500,78400,78300,78200,78100],"bidVolumes":[150,250,350,450,550]}}""");

        Optional<CurrentPriceDetailDto> result = reader.getCurrentPrice(STOCK_CODE);

        assertThat(result).isPresent();
        assertThat(result.get().getCurrentPrice()).isEqualTo(78500L);
    }

    @Test
    @DisplayName("market_data.json 파일이 없으면 빈 값을 반환한다")
    void empty_whenFileMissing() {
        Optional<CurrentPriceDetailDto> result = reader.getCurrentPrice("999999");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("market_data.json에 해당 종목코드 키가 없으면 빈 값을 반환한다")
    void empty_whenStockCodeMissing() throws IOException {
        Files.writeString(tempDir.resolve("market_data.json"), """
                {"000660": {"stockCode":"000660","stockName":"SK하이닉스","currentPrice":220000,
                  "changeAmount":-1000,"changeRate":-0.45,"volume":3456789}}""");

        Optional<CurrentPriceDetailDto> result = reader.getCurrentPrice(STOCK_CODE);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("JSON 파싱에 실패하면 빈 값을 반환한다")
    void empty_whenJsonInvalid() throws IOException {
        Files.writeString(tempDir.resolve("market_data.json"), "이건 JSON이 아니다");

        Optional<CurrentPriceDetailDto> result = reader.getCurrentPrice(STOCK_CODE);

        assertThat(result).isEmpty();
    }

    @Nested
    @DisplayName("과거 시세 스냅샷 조회 (getPriceHistory, price_history.json)")
    class GetPriceHistory {

        @Test
        @DisplayName("dwmcode(1=일봉, 2=주봉, 3=월봉)에 맞는 봉 목록을 최신순 그대로 반환한다")
        void success_readsBarsByDwmcode() throws IOException {
            Files.writeString(tempDir.resolve("price_history.json"), """
                    {"generatedAt":"2026-10-06T15:00:00","source":"ls-t1305","stocks":{
                      "005930":{
                        "day":[{"date":"20261006","open":278500,"high":279000,"low":270000,"close":272250,"volume":10}],
                        "week":[{"date":"20261006","close":272250},{"date":"20260930","close":270000}],
                        "month":[{"date":"20261006","close":272250},{"date":"20260930","close":250000},{"date":"20260831","close":240000}]}}}""");

            assertThat(reader.getPriceHistory(STOCK_CODE, 1)).singleElement()
                    .satisfies(bar -> assertThat(bar.getHigh()).isEqualTo(279000L));
            assertThat(reader.getPriceHistory(STOCK_CODE, 2)).hasSize(2);
            assertThat(reader.getPriceHistory(STOCK_CODE, 3))
                    .extracting(com.teamfp.aistock.infra.marketdata.dto.HistoricalPriceDto::getClose)
                    .containsExactly(272250L, 250000L, 240000L);
        }

        @Test
        @DisplayName("파일이 없거나 종목이 없거나 파싱에 실패하면 빈 목록을 반환한다")
        void empty_whenFileOrStockMissingOrInvalid() throws IOException {
            assertThat(reader.getPriceHistory(STOCK_CODE, 3)).isEmpty();

            Files.writeString(tempDir.resolve("price_history.json"), """
                    {"stocks":{"000660":{"month":[{"date":"20261006","close":1781000}]}}}""");
            assertThat(reader.getPriceHistory(STOCK_CODE, 3)).isEmpty();
            assertThat(reader.getPriceHistory("000660", 1)).isEmpty();

            Files.writeString(tempDir.resolve("price_history.json"), "깨진 JSON");
            // 수정 시각이 같으면 캐시를 다시 쓰므로, 덮어쓴 파일을 확실히 다시 읽도록 시각을 바꾼다.
            tempDir.resolve("price_history.json").toFile().setLastModified(System.currentTimeMillis() + 5_000);
            assertThat(reader.getPriceHistory("000660", 3)).isEmpty();
        }
    }

    @Nested
    @DisplayName("전체 현재가 일괄 조회 (getAllCurrentPrices)")
    class GetAllCurrentPrices {

        @Test
        @DisplayName("market_data.json 전체를 종목코드→현재가 맵으로 한 번에 반환한다")
        void success_readsAllEntries() throws IOException {
            Files.writeString(tempDir.resolve("market_data.json"), """
                    {"005930": {"stockCode":"005930","stockName":"삼성전자","currentPrice":78500,
                      "changeAmount":500,"changeRate":0.64,"volume":12345678,
                      "updatedAt":"2026-09-11T12:00:00"},
                     "000660": {"stockCode":"000660","stockName":"SK하이닉스","currentPrice":220000,
                      "changeAmount":-1000,"changeRate":-0.45,"volume":3456789,
                      "updatedAt":"2026-09-11T12:00:00"}}""");

            Map<String, CurrentPriceDetailDto> result = reader.getAllCurrentPrices();

            assertThat(result).hasSize(2);
            assertThat(result.get("005930").getStockName()).isEqualTo("삼성전자");
            assertThat(result.get("000660").getCurrentPrice()).isEqualTo(220000L);
        }

        @Test
        @DisplayName("market_data.json 파일이 없으면 빈 맵을 반환한다")
        void empty_whenFileMissing() {
            assertThat(reader.getAllCurrentPrices()).isEmpty();
        }

        @Test
        @DisplayName("JSON 파싱에 실패하면 빈 맵을 반환한다")
        void empty_whenJsonInvalid() throws IOException {
            Files.writeString(tempDir.resolve("market_data.json"), "이건 JSON이 아니다");

            assertThat(reader.getAllCurrentPrices()).isEmpty();
        }
    }

    @Nested
    @DisplayName("호가 조회 (getHoga)")
    class GetHoga {

        @Test
        @DisplayName("종목 JSON 객체에 현재가 필드와 호가 필드가 함께 있어도 호가 필드만 뽑아 HogaData로 반환한다")
        void success_readsHogaFieldsFromSameStockObject() throws IOException {
            Files.writeString(tempDir.resolve("market_data.json"), """
                    {
                      "005930": {"stockCode":"005930","stockName":"삼성전자","currentPrice":78500,
                        "changeAmount":500,"changeRate":0.64,"volume":12345678,
                        "askPrices":[78600,78700,78800,78900,79000],
                        "askVolumes":[100,200,300,400,500],
                        "bidPrices":[78500,78400,78300,78200,78100],
                        "bidVolumes":[150,250,350,450,550]}
                    }""");

            Optional<HogaData> result = reader.getHoga(STOCK_CODE);

            assertThat(result).isPresent();
            assertThat(result.get().getStockCode()).isEqualTo("005930");
            assertThat(result.get().getAskPrices()).isEqualTo(List.of(78600L, 78700L, 78800L, 78900L, 79000L));
            assertThat(result.get().getBidPrices()).isEqualTo(List.of(78500L, 78400L, 78300L, 78200L, 78100L));
            assertThat(result.get().getBidVolumes()).isEqualTo(List.of(150L, 250L, 350L, 450L, 550L));
        }

        @Test
        @DisplayName("market_data.json 파일이 없으면 빈 값을 반환한다")
        void empty_whenFileMissing() {
            Optional<HogaData> result = reader.getHoga(STOCK_CODE);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("해당 종목코드 키가 없으면 빈 값을 반환한다")
        void empty_whenStockCodeMissing() throws IOException {
            Files.writeString(tempDir.resolve("market_data.json"), """
                    {"000660": {"stockCode":"000660","askPrices":[1,2,3,4,5],"askVolumes":[1,2,3,4,5],
                      "bidPrices":[1,2,3,4,5],"bidVolumes":[1,2,3,4,5]}}""");

            Optional<HogaData> result = reader.getHoga(STOCK_CODE);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("종목코드 키는 있어도 호가 필드가 없으면(구버전 파일) 빈 값을 반환한다")
        void empty_whenHogaFieldsMissing() throws IOException {
            Files.writeString(tempDir.resolve("market_data.json"), """
                    {"005930": {"stockCode":"005930","stockName":"삼성전자","currentPrice":78500,
                      "changeAmount":500,"changeRate":0.64,"volume":12345678}}""");

            Optional<HogaData> result = reader.getHoga(STOCK_CODE);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("원격 URL 조회 (market-data.url, 배포 사전검증 단계 추가)")
    class ReadFromUrl {

        private HttpServer httpServer;

        @AfterEach
        void tearDown() {
            if (httpServer != null) {
                httpServer.stop(0);
            }
        }

        @Test
        @DisplayName("local-data-url이 설정되어 있으면 파일 대신 그 URL로 GET 요청해 데이터를 가져온다")
        void success_readsFromUrlWhenConfigured() throws IOException {
            String json = """
                    {"005930": {"stockCode":"005930","stockName":"삼성전자","currentPrice":78500,
                      "changeAmount":500,"changeRate":0.64,"volume":12345678}}""";
            httpServer = startStubServer("/market-data", 200, json);
            ReflectionTestUtils.setField(reader, "localDataUrl", serverUrl(httpServer, "/market-data"));

            Optional<CurrentPriceDetailDto> result = reader.getCurrentPrice(STOCK_CODE);

            assertThat(result).isPresent();
            assertThat(result.get().getStockName()).isEqualTo("삼성전자");
        }

        @Test
        @DisplayName("local-data-url이 설정되어 있으면 같은 디렉토리에 market_data.json 파일이 있어도 URL을 우선한다")
        void success_prefersUrlOverLocalFile() throws IOException {
            Files.writeString(tempDir.resolve("market_data.json"), """
                    {"005930": {"stockCode":"005930","stockName":"파일버전","currentPrice":1}}""");
            String json = """
                    {"005930": {"stockCode":"005930","stockName":"URL버전","currentPrice":78500}}""";
            httpServer = startStubServer("/market-data", 200, json);
            ReflectionTestUtils.setField(reader, "localDataUrl", serverUrl(httpServer, "/market-data"));

            Optional<CurrentPriceDetailDto> result = reader.getCurrentPrice(STOCK_CODE);

            assertThat(result).isPresent();
            assertThat(result.get().getStockName()).isEqualTo("URL버전");
        }

        @Test
        @DisplayName("URL 요청이 실패하면(연결 거부) 예외를 던지지 않고 빈 값을 반환한다")
        void empty_whenUrlUnreachable() {
            // 포트 1은 관리자 권한 없이는 바인딩할 수 없는 예약 포트라, 별도 대기 없이 즉시
            // 연결이 거부되는 상황을 안정적으로 재현할 수 있다.
            ReflectionTestUtils.setField(reader, "localDataUrl", "http://localhost:1/market-data");

            Optional<CurrentPriceDetailDto> result = reader.getCurrentPrice(STOCK_CODE);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("gzip 응답을 풀어 읽고, 다음 조회에는 ETag를 보내 304면 다운로드 없이 직전 데이터를 쓴다")
        void success_gzipAndNotModified() throws IOException {
            String json = """
                    {"005930": {"stockCode":"005930","stockName":"삼성전자","currentPrice":263000}}""";
            byte[] gzipped;
            try (var out = new java.io.ByteArrayOutputStream(); var gzip = new java.util.zip.GZIPOutputStream(out)) {
                gzip.write(json.getBytes(StandardCharsets.UTF_8));
                gzip.finish();
                gzipped = out.toByteArray();
            }
            List<String> receivedEtags = new java.util.concurrent.CopyOnWriteArrayList<>();
            httpServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            httpServer.createContext("/market-data", exchange -> {
                String ifNoneMatch = exchange.getRequestHeaders().getFirst("If-None-Match");
                receivedEtags.add(String.valueOf(ifNoneMatch));
                exchange.getResponseHeaders().add("ETag", "\"v1\"");
                if ("\"v1\"".equals(ifNoneMatch)) {
                    exchange.sendResponseHeaders(304, -1);
                    exchange.close();
                    return;
                }
                exchange.getResponseHeaders().add("Content-Encoding", "gzip");
                exchange.sendResponseHeaders(200, gzipped.length);
                try (var responseBody = exchange.getResponseBody()) {
                    responseBody.write(gzipped);
                }
            });
            httpServer.start();
            ReflectionTestUtils.setField(reader, "localDataUrl", serverUrl(httpServer, "/market-data"));

            assertThat(reader.getCurrentPrice(STOCK_CODE)).map(CurrentPriceDetailDto::getCurrentPrice).contains(263000L);
            ReflectionTestUtils.setField(reader, "cachedAtMillis", 0L);  // 1초 캐시를 지나게 한다
            assertThat(reader.getCurrentPrice(STOCK_CODE)).map(CurrentPriceDetailDto::getCurrentPrice).contains(263000L);

            assertThat(receivedEtags).containsExactly("null", "\"v1\"");
        }

        private HttpServer startStubServer(String path, int status, String body) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext(path, exchange -> {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(status, bytes.length);
                try (var responseBody = exchange.getResponseBody()) {
                    responseBody.write(bytes);
                }
            });
            server.start();
            return server;
        }

        private String serverUrl(HttpServer server, String path) {
            return "http://localhost:" + server.getAddress().getPort() + path;
        }
    }
}
