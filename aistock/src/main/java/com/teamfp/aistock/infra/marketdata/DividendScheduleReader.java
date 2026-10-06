package com.teamfp.aistock.infra.marketdata;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.teamfp.aistock.infra.marketdata.dto.DividendScheduleDto;

import lombok.extern.slf4j.Slf4j;

/**
 * local-market-data-generator의 dividend_collector.py가 만든 배당 스케줄 파일
 * ({@code market-data.local-path}/dividends.json)을 읽는다(feature/dividend).
 *
 * <p>{@link RegisteredStockReader}와 같이 파일이 없거나 깨져 있어도 예외를 던지지 않고 빈 목록을
 * 반환한다 — generator가 없는 팀원 PC나 local-path가 비어 있는 prod에서도 서버 기동을 막지 않기
 * 위함이다. 다만 stocks.json과 달리 배당 공시가 나올 때마다 다시 만들어지는 파일이라 classpath에
 * 복사해 두지 않고, 호출될 때마다(서버 기동 시·관리자 재적재 시) 디스크에서 새로 읽는다.</p>
 *
 * <p>파일 형식: {@code {"generatedAt": "...", "items": [{stockCode, fiscalYear, period, dividendKind,
 * dpsCash, recordDate(yyyyMMdd), exDividendDate, payDate, dividendYield, source}, ...]}}.
 * 필수값(stockCode·fiscalYear·period)이 없거나 날짜 형식이 틀린 항목은 건너뛴다.</p>
 */
@Slf4j
@Component
public class DividendScheduleReader {

    static final String DIVIDEND_FILE_NAME = "dividends.json";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final DateTimeFormatter FILE_DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;

    private final String localDataPath;

    public DividendScheduleReader(@Value("${market-data.local-path:}") String localDataPath) {
        this.localDataPath = localDataPath;
    }

    /** dividends.json의 배당 회차 전체(파일 순서 유지). 파일이 없거나 읽지 못하면 빈 리스트. */
    public List<DividendScheduleDto> readDividendSchedules() {
        if (localDataPath == null || localDataPath.isBlank()) {
            log.warn("market-data.local-path가 비어 있어 배당 스케줄 파일을 읽지 않음");
            return List.of();
        }
        Path filePath = Path.of(localDataPath, DIVIDEND_FILE_NAME);
        if (!Files.exists(filePath)) {
            log.warn("배당 스케줄 파일을 찾지 못함 - path: {}", filePath.toAbsolutePath());
            return List.of();
        }
        try {
            JsonNode items = OBJECT_MAPPER.readTree(filePath.toFile()).path("items");
            List<DividendScheduleDto> schedules = new ArrayList<>();
            for (JsonNode item : items) {
                DividendScheduleDto schedule = parseItem(item);
                if (schedule != null) {
                    schedules.add(schedule);
                }
            }
            return List.copyOf(schedules);
        } catch (IOException e) {
            log.warn("배당 스케줄 파일 파싱 실패 - path: {}, 사유: {}", filePath.toAbsolutePath(), e.getMessage());
            return List.of();
        }
    }

    private static DividendScheduleDto parseItem(JsonNode item) {
        String stockCode = textOrNull(item, "stockCode");
        String fiscalYear = textOrNull(item, "fiscalYear");
        String period = textOrNull(item, "period");
        if (stockCode == null || fiscalYear == null || period == null) {
            log.warn("배당 스케줄 필수값 누락으로 건너뜀 - item: {}", item);
            return null;
        }
        try {
            JsonNode dpsCash = item.path("dpsCash");
            JsonNode dividendYield = item.path("dividendYield");
            return new DividendScheduleDto(
                    stockCode,
                    fiscalYear,
                    period,
                    textOrNull(item, "dividendKind"),
                    dpsCash.isNumber() ? dpsCash.asInt() : null,
                    dateOrNull(item, "recordDate"),
                    dateOrNull(item, "exDividendDate"),
                    dateOrNull(item, "payDate"),
                    dividendYield.isNumber() ? dividendYield.decimalValue() : null,
                    textOrNull(item, "source"));
        } catch (DateTimeParseException e) {
            log.warn("배당 스케줄 날짜 형식 오류로 건너뜀 - stockCode: {}, {} {}, 사유: {}",
                    stockCode, fiscalYear, period, e.getMessage());
            return null;
        }
    }

    private static String textOrNull(JsonNode item, String fieldName) {
        JsonNode node = item.path(fieldName);
        return node.isTextual() && !node.asText().isBlank() ? node.asText() : null;
    }

    private static LocalDate dateOrNull(JsonNode item, String fieldName) {
        String text = textOrNull(item, fieldName);
        return text != null ? LocalDate.parse(text, FILE_DATE_FORMAT) : null;
    }
}
