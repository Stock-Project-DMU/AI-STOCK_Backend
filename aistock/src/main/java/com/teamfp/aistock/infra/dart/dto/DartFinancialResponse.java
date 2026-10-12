package com.teamfp.aistock.infra.dart.dto;

/**
 * 재무제표 원본 계정 목록 전체가 아니라, AI 상담 프롬프트에 바로 넣기 좋도록 핵심 지표만
 * 추려서 담는다. 값이 없는 계정(공시에 없거나 파싱 실패)은 null로 둔다.
 *
 * <p>reportCode(11011 사업보고서/11012 반기/11013 1분기/11014 3분기)·fsDiv(CFS 연결/OFS 개별)·receiptNo(DART 접수번호)는
 * 종목 리서치 화면이 "어느 보고서의 값인지"를 보여주고 DART 원문으로 바로 대조할 수 있게 하려고 담는다
 * (fix/stock-detail-review). 공시를 못 찾은 빈 응답이면 셋 다 null이다.</p>
 */
public record DartFinancialResponse(
        String corpCode,
        int bizYear,
        Long revenue,
        Long operatingProfit,
        Long netIncome,
        Long totalAssets,
        Long totalLiabilities,
        Long totalEquity,
        String reportCode,
        String fsDiv,
        String receiptNo
) {
}
