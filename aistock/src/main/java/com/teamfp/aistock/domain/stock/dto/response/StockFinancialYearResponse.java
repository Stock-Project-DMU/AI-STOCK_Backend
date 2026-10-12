package com.teamfp.aistock.domain.stock.dto.response;

import com.teamfp.aistock.infra.dart.dto.DartFinancialDetailResponse;

/**
 * 종목 리서치 "재무제표" 탭의 한 해(개요 탭의 재무 지표에도 최신 연도를 쓴다, fix/stock-detail-review). 금액은 원 단위(eps는 원)이고,
 * 손익·현금흐름은 그 해 1~12월 합계, 재무상태는 12월 31일 기준이다. 공시에 없는 계정과 계산할 수 없는 비율은 null이다.
 *
 * <p>비율(%)은 소수 둘째 자리 반올림 — 영업이익률·순이익률 = 영업이익·당기순이익 ÷ 매출액, ROE = 지배주주 순이익 ÷ 지배주주 지분
 * (지배주주 값이 없으면 전체 순이익 ÷ 전체 자본), ROA = 당기순이익 ÷ 자산총계, 부채비율 = 부채총계 ÷ 자본총계, 유동비율 = 유동자산 ÷
 * 유동부채. 금융업(isFinancialCompany)은 매출액이 없어 영업이익률·순이익률이 null이고, 유동·비유동 구분이 없어 유동비율도 대개 null이다.</p>
 */
public record StockFinancialYearResponse(
        int bizYear,
        boolean isFinancialCompany,
        Long revenue,
        Long grossProfit,
        Long operatingProfit,
        Long profitBeforeTax,
        Long netIncome,
        Long eps,
        Long currentAssets,
        Long currentLiabilities,
        Long cashAndEquivalents,
        Long totalAssets,
        Long totalLiabilities,
        Long issuedCapital,
        Long retainedEarnings,
        Long totalEquity,
        Long operatingCashFlow,
        Long investingCashFlow,
        Long financingCashFlow,
        Double operatingMargin,
        Double netMargin,
        Double roe,
        Double roa,
        Double debtRatio,
        Double currentRatio
) {

    public static StockFinancialYearResponse from(DartFinancialDetailResponse detail) {
        boolean hasOwnerValues = detail.netIncomeOwners() != null && detail.equityOwners() != null;
        Double roe = hasOwnerValues
                ? percent(detail.netIncomeOwners(), detail.equityOwners())
                : percent(detail.netIncome(), detail.totalEquity());
        return new StockFinancialYearResponse(detail.bizYear(), detail.isFinancialCompany(),
                detail.revenue(), detail.grossProfit(), detail.operatingProfit(), detail.profitBeforeTax(), detail.netIncome(),
                detail.eps(), detail.currentAssets(), detail.currentLiabilities(), detail.cashAndEquivalents(),
                detail.totalAssets(), detail.totalLiabilities(), detail.issuedCapital(), detail.retainedEarnings(), detail.totalEquity(),
                detail.operatingCashFlow(), detail.investingCashFlow(), detail.financingCashFlow(),
                percent(detail.operatingProfit(), detail.revenue()),
                percent(detail.netIncome(), detail.revenue()),
                roe,
                percent(detail.netIncome(), detail.totalAssets()),
                percent(detail.totalLiabilities(), detail.totalEquity()),
                percent(detail.currentAssets(), detail.currentLiabilities()));
    }

    // 분모가 0 이하(자본잠식 등)면 비율이 의미가 없어 null.
    private static Double percent(Long numerator, Long denominator) {
        if (numerator == null || denominator == null || denominator <= 0) {
            return null;
        }
        return Math.round(numerator * 10000.0 / denominator) / 100.0;
    }
}
