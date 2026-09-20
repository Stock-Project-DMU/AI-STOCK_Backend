package com.teamfp.aistock.infra.ls;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import com.teamfp.aistock.infra.ls.dto.LsCurrentPriceDetailDto;
import com.teamfp.aistock.infra.ls.dto.LsHogaData;

class LsLocalMarketDataReaderTest {

    private static final String STOCK_CODE = "005930";

    @TempDir
    private Path tempDir;

    private LsLocalMarketDataReader reader;

    @BeforeEach
    void setUp() {
        reader = new LsLocalMarketDataReader();
        ReflectionTestUtils.setField(reader, "localDataPath", tempDir.toString());
    }

    @Test
    @DisplayName("market_data.json에서 종목코드 키를 찾아 LsCurrentPriceDetailDto로 반환한다")
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

        Optional<LsCurrentPriceDetailDto> result = reader.getCurrentPrice(STOCK_CODE);

        assertThat(result).isPresent();
        assertThat(result.get().getStockName()).isEqualTo("삼성전자");
        assertThat(result.get().getCurrentPrice()).isEqualTo(78500L);
        assertThat(result.get().getPer()).isEqualTo(15.2);
        assertThat(result.get().getUpdatedAt()).isEqualTo(java.time.LocalDateTime.of(2026, 9, 11, 12, 0, 0));
    }

    @Test
    @DisplayName("같은 종목 JSON 객체에 호가 필드(askPrices 등)가 섞여 있어도 현재가 필드는 그대로 읽는다")
    void success_ignoresHogaFieldsMixedIntoSameObject() throws IOException {
        Files.writeString(tempDir.resolve("market_data.json"), """
                {"005930": {"stockCode":"005930","stockName":"삼성전자","currentPrice":78500,
                  "changeAmount":500,"changeRate":0.64,"volume":12345678,
                  "askPrices":[78600,78700,78800,78900,79000],"askVolumes":[100,200,300,400,500],
                  "bidPrices":[78500,78400,78300,78200,78100],"bidVolumes":[150,250,350,450,550]}}""");

        Optional<LsCurrentPriceDetailDto> result = reader.getCurrentPrice(STOCK_CODE);

        assertThat(result).isPresent();
        assertThat(result.get().getCurrentPrice()).isEqualTo(78500L);
    }

    @Test
    @DisplayName("market_data.json 파일이 없으면 빈 값을 반환한다")
    void empty_whenFileMissing() {
        Optional<LsCurrentPriceDetailDto> result = reader.getCurrentPrice("999999");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("market_data.json에 해당 종목코드 키가 없으면 빈 값을 반환한다")
    void empty_whenStockCodeMissing() throws IOException {
        Files.writeString(tempDir.resolve("market_data.json"), """
                {"000660": {"stockCode":"000660","stockName":"SK하이닉스","currentPrice":220000,
                  "changeAmount":-1000,"changeRate":-0.45,"volume":3456789}}""");

        Optional<LsCurrentPriceDetailDto> result = reader.getCurrentPrice(STOCK_CODE);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("JSON 파싱에 실패하면 빈 값을 반환한다")
    void empty_whenJsonInvalid() throws IOException {
        Files.writeString(tempDir.resolve("market_data.json"), "이건 JSON이 아니다");

        Optional<LsCurrentPriceDetailDto> result = reader.getCurrentPrice(STOCK_CODE);

        assertThat(result).isEmpty();
    }

    @Nested
    @DisplayName("호가 조회 (getHoga)")
    class GetHoga {

        @Test
        @DisplayName("종목 JSON 객체에 현재가 필드와 호가 필드가 함께 있어도 호가 필드만 뽑아 LsHogaData로 반환한다")
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

            Optional<LsHogaData> result = reader.getHoga(STOCK_CODE);

            assertThat(result).isPresent();
            assertThat(result.get().getStockCode()).isEqualTo("005930");
            assertThat(result.get().getAskPrices()).isEqualTo(List.of(78600L, 78700L, 78800L, 78900L, 79000L));
            assertThat(result.get().getBidPrices()).isEqualTo(List.of(78500L, 78400L, 78300L, 78200L, 78100L));
            assertThat(result.get().getBidVolumes()).isEqualTo(List.of(150L, 250L, 350L, 450L, 550L));
        }

        @Test
        @DisplayName("market_data.json 파일이 없으면 빈 값을 반환한다")
        void empty_whenFileMissing() {
            Optional<LsHogaData> result = reader.getHoga(STOCK_CODE);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("해당 종목코드 키가 없으면 빈 값을 반환한다")
        void empty_whenStockCodeMissing() throws IOException {
            Files.writeString(tempDir.resolve("market_data.json"), """
                    {"000660": {"stockCode":"000660","askPrices":[1,2,3,4,5],"askVolumes":[1,2,3,4,5],
                      "bidPrices":[1,2,3,4,5],"bidVolumes":[1,2,3,4,5]}}""");

            Optional<LsHogaData> result = reader.getHoga(STOCK_CODE);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("종목코드 키는 있어도 호가 필드가 없으면(구버전 파일) 빈 값을 반환한다")
        void empty_whenHogaFieldsMissing() throws IOException {
            Files.writeString(tempDir.resolve("market_data.json"), """
                    {"005930": {"stockCode":"005930","stockName":"삼성전자","currentPrice":78500,
                      "changeAmount":500,"changeRate":0.64,"volume":12345678}}""");

            Optional<LsHogaData> result = reader.getHoga(STOCK_CODE);

            assertThat(result).isEmpty();
        }
    }
}
