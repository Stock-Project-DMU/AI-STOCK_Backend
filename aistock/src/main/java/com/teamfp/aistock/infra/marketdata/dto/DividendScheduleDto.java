package com.teamfp.aistock.infra.marketdata.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * local-market-data-generator가 만든 dividends.json의 배당 회차 1건. {@link
 * com.teamfp.aistock.infra.marketdata.DividendScheduleReader}가 파일의 yyyyMMdd 문자열 날짜를
 * LocalDate로 바꿔 담는다. dpsCash·payDate 등은 공시 전이면 null일 수 있다.
 */
public record DividendScheduleDto(
        String stockCode,
        String fiscalYear,
        String period,
        String dividendKind,
        Integer dpsCash,
        LocalDate recordDate,
        LocalDate exDividendDate,
        LocalDate payDate,
        BigDecimal dividendYield,
        String source
) {
}
