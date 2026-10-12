package com.teamfp.aistock.infra.dart.dto;

/**
 * 가장 최근 분기의 손익과 전년 같은 분기 손익(fix/stock-detail-review) — 종목 리서치 "분기 실적" 탭용.
 *
 * <p>분기·반기 보고서(11013/11012/11014)면 그 분기 3개월치(DART thstrm_amount)와 전년 같은 분기 3개월치(frmtrm_q_amount)이고,
 * 분기 보고서를 못 찾아 사업보고서(11011)로 대체했으면 한 해 전체 값과 전년 값(frmtrm_amount)이다. 공시를 못 찾으면
 * reportCode를 포함한 값이 전부 null이다.</p>
 *
 * @param isFinancialCompany 금융업(매출액 대신 이자수익·보험수익 등으로 공시) — revenue·previousRevenue가 null이다
 */
public record DartEarningsResponse(
        String corpCode,
        int bizYear,
        String reportCode,
        String fsDiv,
        String receiptNo,
        boolean isFinancialCompany,
        Long revenue,
        Long operatingProfit,
        Long netIncome,
        Long previousRevenue,
        Long previousOperatingProfit,
        Long previousNetIncome
) {
}
