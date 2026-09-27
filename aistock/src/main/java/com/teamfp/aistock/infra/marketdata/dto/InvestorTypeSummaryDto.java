package com.teamfp.aistock.infra.marketdata.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 외부 시세 데이터 제공사 Open API 투자자별종합(t1601) — 코스피 시장 전체 투자자유형별 순매수 스냅샷. */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class InvestorTypeSummaryDto {

    private Long individualNetBuy; // 개인
    private Long foreignNetBuy; // 외국인
    private Long institutionNetBuy; // 기관계
    private Long securitiesNetBuy; // 증권
    private Long insuranceNetBuy; // 보험
    private Long investmentTrustNetBuy; // 투신
}
