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
    @DisplayName("{stockCode}.json을 읽어 LsCurrentPriceDetailDto로 반환한다")
    void success_readsJsonFile() throws IOException {
        Files.writeString(tempDir.resolve(STOCK_CODE + ".json"), """
                {"stockCode":"005930","stockName":"삼성전자","currentPrice":78500,
                 "changeAmount":500,"changeRate":0.64,"volume":12345678,
                 "per":15.2,"pbr":1.3,"high52w":85000,"high52wDate":"20260101",
                 "low52w":60000,"low52wDate":"20250601"}""");

        Optional<LsCurrentPriceDetailDto> result = reader.getCurrentPrice(STOCK_CODE);

        assertThat(result).isPresent();
        assertThat(result.get().getStockName()).isEqualTo("삼성전자");
        assertThat(result.get().getCurrentPrice()).isEqualTo(78500L);
        assertThat(result.get().getPer()).isEqualTo(15.2);
    }

    @Test
    @DisplayName("파일이 없으면 빈 값을 반환한다")
    void empty_whenFileMissing() {
        Optional<LsCurrentPriceDetailDto> result = reader.getCurrentPrice("999999");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("JSON 파싱에 실패하면 빈 값을 반환한다")
    void empty_whenJsonInvalid() throws IOException {
        Files.writeString(tempDir.resolve(STOCK_CODE + ".json"), "이건 JSON이 아니다");

        Optional<LsCurrentPriceDetailDto> result = reader.getCurrentPrice(STOCK_CODE);

        assertThat(result).isEmpty();
    }
}
