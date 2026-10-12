package com.teamfp.aistock.infra.dart.dto;

import java.util.List;

/**
 * DART "배당에 관한 사항"(alotMatter.json) 중 배당 판단에 필요한 지표만 추린 결과(fix/stock-detail-review).
 *
 * <p>사업보고서 기준이라 당기·전기·전전기가 각각 한 해(1~12월) 전체 값이다. 값은 DART 원문("1,668", "25.10")을 숫자로
 * 바꾼 것이고(단위는 항목명 괄호 안 — 원, %, 백만원, 주), 공시에 "-"로 나온 값은 null이다. 공시를 못 찾으면 indicators가
 * 빈 목록이다.</p>
 *
 * @param bizYear        사업연도(당기)
 * @param reportCode     보고서 종류(11011 사업보고서)
 * @param receiptNo      DART 접수번호 — 공시 원문 주소를 만들 때 쓴다
 * @param settlementDate 결산기준일(예: 2025-12-31)
 */
public record DartDividendResponse(
        String corpCode,
        int bizYear,
        String reportCode,
        String receiptNo,
        String settlementDate,
        List<DividendIndicator> indicators
) {

    /**
     * 배당 지표 한 줄.
     *
     * @param label     DART 항목명(예: "주당 현금배당금(원)", "(연결)현금배당성향(%)")
     * @param stockKind 주식 종류("보통주"/"우선주", 종류 구분이 없는 항목은 null)
     */
    public record DividendIndicator(
            String label,
            String stockKind,
            Double currentValue,
            Double previousValue,
            Double beforePreviousValue
    ) {
    }
}
