package com.teamfp.aistock.infra.dart.dto;

/**
 * 재무제표 원본 계정 목록 전체가 아니라, AI 상담 프롬프트에 바로 넣기 좋도록 핵심 지표만
 * 추려서 담는다. 값이 없는 계정(공시에 없거나 파싱 실패)은 null로 둔다.
 */
public record DartFinancialResponse(
        String corpCode,
        int bizYear,
        Long revenue,
        Long operatingProfit,
        Long netIncome,
        Long totalAssets,
        Long totalLiabilities,
        Long totalEquity
) {
}
