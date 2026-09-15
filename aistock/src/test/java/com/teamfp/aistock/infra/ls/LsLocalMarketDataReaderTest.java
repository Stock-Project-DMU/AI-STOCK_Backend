package com.teamfp.aistock.infra.ls;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import com.teamfp.aistock.infra.ls.dto.LsCurrentPriceDetailDto;

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
}
