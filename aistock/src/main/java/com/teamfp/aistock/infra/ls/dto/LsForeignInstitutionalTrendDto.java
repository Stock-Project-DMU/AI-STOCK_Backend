package com.teamfp.aistock.infra.ls.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * LS증권 Open API 외인기관종목별동향(t1716) 응답 한 건(하루치)을 담는 DTO.
 *
 * DART의 "대량보유상황보고"(개별 투자자가 5% 이상 보유하게 됐을 때만 나오는 1회성 신고)와는
 * 성격이 다르다 — 이건 매일 시장에서 실제로 체결된 전체 외국인·기관·개인 순매수와, 외국인
 * 보유한도 대비 소진율(전체 외국인 보유 비중)을 담는 일별 동향 데이터다. AiPlanningService의
 * get_foreign_institutional_trend 도구 설명에도 이 구분을 명시해, DART 도구와 겹치지 않게 한다.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LsForeignInstitutionalTrendDto {

    private String date;                  // 일자(YYYYMMDD)
    private long closePrice;              // 종가
    private long individualNetBuyKrx;     // KRX 기준 개인 순매수(수량)
    private long institutionNetBuyKrx;    // KRX 기준 기관 순매수(수량)
    private long foreignNetBuyKrx;        // KRX 기준 외국인 순매수(수량)
    private long programTradingVolume;    // 프로그램매매 거래량
    private Long foreignHoldingShares;    // 금융감독원 기준 외국인 보유 주식수
    private Double foreignExhaustionRate; // 금융감독원 기준 외국인 보유한도 소진율(%)
    private long shortSellingVolume;      // 공매도 수량
    private long shortSellingValue;       // 공매도 대금
}
