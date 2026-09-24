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
