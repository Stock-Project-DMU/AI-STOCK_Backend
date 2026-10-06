package com.teamfp.aistock.domain.stock.dto.response;

import java.time.LocalDate;

import com.teamfp.aistock.domain.stock.entity.DividendEntitlement;
import com.teamfp.aistock.domain.stock.entity.DividendSchedule;

/**
 * 내 예정 배당 1건(GET /api/dividends/upcoming, feature/dividend). 두 종류가 섞여 있다.
 * <ul>
 *   <li>isEntitled=true — 배당락일이 지나 권리가 확정되고 지급을 기다리는 회차(PENDING 권리).
 *       quantity·expectedAmount는 배당락일 기준 확정값이다.</li>
 *   <li>isEntitled=false — 지금 보유 중인 종목의 배당락일이 아직 오지 않은 회차. quantity는 현재 보유
 *       수량이고 expectedAmount = 현재 보유 수량 × dpsCash(배당금 미공시면 null)인 예상치다.</li>
 * </ul>
 * payDate는 실제 지급 예정일이며, 지급일이 공시되지 않았으면 기준일 + 45일 대체값이다(isPayDateEstimated=true).
 */
public record UpcomingDividendResponse(
        Long dividendScheduleId,
        String stockCode,
        String stockName,
        String fiscalYear,
        String period,
        LocalDate exDividendDate,
        LocalDate payDate,
        boolean isPayDateEstimated,
        Integer dpsCash,
        int quantity,
        Long expectedAmount,
        boolean isEntitled
) {

    public static UpcomingDividendResponse ofEntitlement(DividendEntitlement entitlement, String stockName) {
        DividendSchedule schedule = entitlement.getDividendSchedule();
        return new UpcomingDividendResponse(
                schedule.getDividendScheduleId(),
                entitlement.getStockCode(),
                stockName,
                schedule.getFiscalYear(),
                schedule.getPeriod(),
                entitlement.getExDividendDate(),
                entitlement.resolvePayDate(),
                entitlement.getPayDate() == null && schedule.isPayDateEstimated(),
                entitlement.getDpsCash(),
                entitlement.getQuantity(),
                entitlement.getTotalAmount(),
                true
        );
    }

    public static UpcomingDividendResponse ofSchedule(DividendSchedule schedule, String stockName, int quantity) {
        Integer dpsCash = schedule.getDpsCash();
        return new UpcomingDividendResponse(
                schedule.getDividendScheduleId(),
                schedule.getStockCode(),
                stockName,
                schedule.getFiscalYear(),
                schedule.getPeriod(),
                schedule.getExDividendDate(),
                schedule.resolvePayDate(),
                schedule.isPayDateEstimated(),
                dpsCash,
                quantity,
                dpsCash != null ? (long) quantity * dpsCash : null,
                false
        );
    }
}
