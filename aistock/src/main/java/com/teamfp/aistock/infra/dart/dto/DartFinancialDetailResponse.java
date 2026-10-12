package com.teamfp.aistock.infra.dart.dto;

/**
 * 종목 리서치 "재무제표"·"개요" 탭용 사업보고서 한 해의 상세 계정(fix/stock-detail-review). 금액은 원 단위, eps는 원이다.
 * AI 상담용 {@link DartFinancialResponse}(핵심 6개 계정)와 달리 손익·재무상태·현금흐름을 함께 담는다. 공시에 없는 계정은 null.
 *
 * @param isFinancialCompany 은행·보험·증권 등 금융업 — 매출액(ifrs-full_Revenue) 대신 이자수익·보험수익 등으로 공시해 매출 관련
 *                           항목(revenue, grossProfit)이 없다
 * @param netIncomeOwners    지배기업 소유주 귀속 당기순이익(ROE 계산용)
 * @param equityOwners       지배기업 소유주 지분(ROE 계산용)
 */
public record DartFinancialDetailResponse(
        String corpCode,
        int bizYear,
        String reportCode,
        String fsDiv,
        String receiptNo,
        boolean isFinancialCompany,
        Long revenue,
        Long grossProfit,
        Long operatingProfit,
        Long profitBeforeTax,
        Long netIncome,
        Long netIncomeOwners,
        Long eps,
        Long currentAssets,
        Long currentLiabilities,
        Long cashAndEquivalents,
        Long totalAssets,
        Long totalLiabilities,
        Long issuedCapital,
        Long retainedEarnings,
        Long totalEquity,
        Long equityOwners,
        Long operatingCashFlow,
        Long investingCashFlow,
        Long financingCashFlow
) {
}
