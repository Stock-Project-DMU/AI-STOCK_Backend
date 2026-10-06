package com.teamfp.aistock.domain.stock.dto.response;

import java.time.LocalDateTime;

import com.teamfp.aistock.domain.stock.entity.DividendEntitlement;

/**
 * 내 배당 수령 내역 1건(GET /api/dividends/my, feature/dividend). 지급 완료(PAID)된 권리만 담는다.
 */
public record DividendEntitlementResponse(
        Long dividendEntitlementId,
        String stockCode,
        String stockName,
        String fiscalYear,
        String period,
        int quantity,
        int dpsCash,
        long totalAmount,
        LocalDateTime paidAt
) {

    public static DividendEntitlementResponse of(DividendEntitlement entitlement, String stockName) {
        return new DividendEntitlementResponse(
                entitlement.getDividendEntitlementId(),
                entitlement.getStockCode(),
                stockName,
                entitlement.getDividendSchedule().getFiscalYear(),
                entitlement.getDividendSchedule().getPeriod(),
                entitlement.getQuantity(),
                entitlement.getDpsCash(),
                entitlement.getTotalAmount(),
                entitlement.getPaidAt()
        );
    }
}
