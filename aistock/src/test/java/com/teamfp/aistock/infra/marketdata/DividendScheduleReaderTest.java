package com.teamfp.aistock.infra.marketdata;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.teamfp.aistock.infra.marketdata.dto.DividendScheduleDto;

class DividendScheduleReaderTest {

    // local-market-data-generator가 실제로 만든 dividends.json(2026-10-05)에서 뽑은 3건 + 필수값이 빠진 1건.
    // 현대차는 지급일 미공시(payDate null), 삼성전자 2026 Q3는 배당결정 공시 전(dpsCash·dividendYield null).
    private static final String SAMPLE_JSON = """
            {
              "generatedAt": "2026-10-05T20:14:01",
              "items": [
                {"stockCode": "005380", "fiscalYear": "2025", "period": "Q4", "dividendKind": "QUARTERLY", "dpsCash": 2500,
                 "recordDate": "20260228", "exDividendDate": "20260227", "payDate": null, "dividendYield": 1.9, "source": "LS_t3202+DART"},
                {"stockCode": "005930", "fiscalYear": "2026", "period": "Q2", "dividendKind": "QUARTERLY", "dpsCash": 374,
                 "recordDate": "20260630", "exDividendDate": "20260629", "payDate": "20260828", "dividendYield": 0.2, "source": "LS_t3202+DART"},
                {"stockCode": "005930", "fiscalYear": "2026", "period": "Q3", "dividendKind": "QUARTERLY", "dpsCash": null,
                 "recordDate": "20260930", "exDividendDate": "20260929", "payDate": null, "dividendYield": null, "source": "LS_t3202"},
                {"stockCode": "000660", "fiscalYear": null, "period": "Q1", "dpsCash": 375}
              ]
            }
            """;

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("dividends.json의 배당 회차를 날짜·금액 타입으로 변환해 읽고, null 필드는 null로 유지한다")
    void readsDividendSchedules() throws IOException {
        Files.writeString(tempDir.resolve(DividendScheduleReader.DIVIDEND_FILE_NAME), SAMPLE_JSON);

        List<DividendScheduleDto> schedules = new DividendScheduleReader(tempDir.toString()).readDividendSchedules();

        assertThat(schedules).hasSize(3); // fiscalYear가 없는 000660 항목은 건너뜀
        assertThat(schedules.get(0)).isEqualTo(new DividendScheduleDto("005380", "2025", "Q4", "QUARTERLY", 2500,
                LocalDate.of(2026, 2, 28), LocalDate.of(2026, 2, 27), null, new BigDecimal("1.9"), "LS_t3202+DART"));
        assertThat(schedules.get(1)).isEqualTo(new DividendScheduleDto("005930", "2026", "Q2", "QUARTERLY", 374,
                LocalDate.of(2026, 6, 30), LocalDate.of(2026, 6, 29), LocalDate.of(2026, 8, 28), new BigDecimal("0.2"),
                "LS_t3202+DART"));

        DividendScheduleDto undisclosed = schedules.get(2);
        assertThat(undisclosed.dpsCash()).isNull();
        assertThat(undisclosed.dividendYield()).isNull();
        assertThat(undisclosed.payDate()).isNull();
        assertThat(undisclosed.exDividendDate()).isEqualTo(LocalDate.of(2026, 9, 29));
    }

    @Test
    @DisplayName("파일이 없거나 경로가 비어 있으면 예외 없이 빈 목록을 반환한다(서버 기동은 막지 않음)")
    void missingFile_returnsEmptyList() {
        assertThat(new DividendScheduleReader(tempDir.toString()).readDividendSchedules()).isEmpty();
        assertThat(new DividendScheduleReader("").readDividendSchedules()).isEmpty();
    }
}
