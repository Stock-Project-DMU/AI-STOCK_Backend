package com.teamfp.aistock.domain.stock.dto.response;

/**
 * 종목 리서치 탭 한 개의 응답(fix/stock-detail-review) — 섹션 데이터(content)에 "어디서 가져온 값인지"를 함께 담아,
 * 화면이 출처를 보여주고 사용자가 DART 원문 등과 직접 대조할 수 있게 한다.
 *
 * @param section     요청한 섹션(finance, earnings, dividend, peers, analysts)
 * @param sourceName  출처 이름(예: "DART 사업보고서", "모의 시세 데이터")
 * @param sourceDetail 출처 상세(보고서 기간·재무제표 종류·DART API 이름 등)
 * @param sourceUrl   원문 바로가기(DART 공시 뷰어). 없으면 null
 * @param isSimulated 실제 데이터가 아닌 local-market-data-generator 모의 데이터인지
 * @param notice      화면에 함께 보여줄 안내(값의 기간 기준, ETF 미제공 사유 등). 없으면 null
 * @param content     섹션 데이터. 제공할 자료가 없으면 null
 */
public record StockResearchResponse(
        String section,
        String sourceName,
        String sourceDetail,
        String sourceUrl,
        boolean isSimulated,
        String notice,
        Object content
) {

    /** 실제 공시 데이터(DART). */
    public static StockResearchResponse of(String section, String sourceName, String sourceDetail, String sourceUrl,
                                           String notice, Object content) {
        return new StockResearchResponse(section, sourceName, sourceDetail, sourceUrl, false, notice, content);
    }

    /** local-market-data-generator가 만든 모의 데이터. */
    public static StockResearchResponse simulated(String section, String sourceDetail, String notice, Object content) {
        return new StockResearchResponse(section, "모의 시세 데이터", sourceDetail, null, true, notice, content);
    }

    /** 이 종목에는 제공할 자료가 없음(ETF처럼 DART 공시 대상이 아닌 경우 등) — 오류 대신 안내로 응답한다. */
    public static StockResearchResponse unavailable(String section, String notice) {
        return new StockResearchResponse(section, null, null, null, false, notice, null);
    }
}
