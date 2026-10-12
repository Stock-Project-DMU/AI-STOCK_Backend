package com.teamfp.aistock.domain.stock.dto.response;

import com.teamfp.aistock.infra.dart.dto.DartEarningsResponse;

/**
 * 종목 리서치 "분기 실적" 탭(fix/stock-detail-review) — 최근 분기 손익과 전년 같은 분기 대비 증감률. 금액은 원 단위다.
 *
 * <p>증감률(%)은 (이번 − 전년) ÷ 전년 × 100, 소수 둘째 자리 반올림. 전년 값이 없거나 0 이하(적자)면 증감률이 의미가 없어
 * null이다 — 이때 화면은 두 값의 부호로 흑자 전환·적자 지속 등을 표시한다.</p>
 *
 * @param reportCode         11013 1분기, 11012 반기(2분기), 11014 3분기, 11011 사업보고서(분기 보고서를 못 찾은 경우 — 연간 값)
 * @param isFinancialCompany 금융업 — 매출액 대신 이자수익·보험수익 등으로 공시해 매출 관련 값이 null이다
 */
public record StockEarningsResponse(
        int bizYear,
        String reportCode,
        boolean isFinancialCompany,
        Long revenue,
        Long operatingProfit,
        Long netIncome,
        Long previousRevenue,
        Long previousOperatingProfit,
        Long previousNetIncome,
        Double revenueGrowthRate,
        Double operatingProfitGrowthRate,
        Double netIncomeGrowthRate
) {

    public static StockEarningsResponse from(DartEarningsResponse earnings) {
        return new StockEarningsResponse(earnings.bizYear(), earnings.reportCode(), earnings.isFinancialCompany(),
                earnings.revenue(), earnings.operatingProfit(), earnings.netIncome(),
                earnings.previousRevenue(), earnings.previousOperatingProfit(), earnings.previousNetIncome(),
                growthRate(earnings.revenue(), earnings.previousRevenue()),
                growthRate(earnings.operatingProfit(), earnings.previousOperatingProfit()),
                growthRate(earnings.netIncome(), earnings.previousNetIncome()));
    }

    private static Double growthRate(Long current, Long previous) {
        if (current == null || previous == null || previous <= 0) {
            return null;
        }
        return Math.round((current - previous) * 10000.0 / previous) / 100.0;
    }
}
