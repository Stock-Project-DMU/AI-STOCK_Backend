package com.teamfp.aistock.domain.stock.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.teamfp.aistock.domain.stock.entity.DividendSchedule;

/**
 * 배당 스케줄 1건(GET /api/dividends/schedule, feature/dividend). dpsCash·payDate는 공시 전이면 null이다.
 */
public record DividendScheduleResponse(
        Long dividendScheduleId,
        String stockCode,
        String stockName,
        String fiscalYear,
        String period,
        String dividendKind,
        Integer dpsCash,
        LocalDate recordDate,
        LocalDate exDividendDate,
        LocalDate payDate,
        BigDecimal dividendYield
) {

    public static DividendScheduleResponse of(DividendSchedule schedule, String stockName) {
        return new DividendScheduleResponse(
                schedule.getDividendScheduleId(),
                schedule.getStockCode(),
                stockName,
                schedule.getFiscalYear(),
                schedule.getPeriod(),
                schedule.getDividendKind(),
                schedule.getDpsCash(),
                schedule.getRecordDate(),
                schedule.getExDividendDate(),
                schedule.getPayDate(),
                schedule.getDividendYield()
        );
    }
}
