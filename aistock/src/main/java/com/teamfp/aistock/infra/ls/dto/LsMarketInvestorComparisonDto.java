package com.teamfp.aistock.infra.ls.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** LS증권 Open API 투자자매매종합1(t1615) — 코스피/코스닥/선물/옵션 등 시장별 투자자 순매수 비교. */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LsMarketInvestorComparisonDto {

    private String marketName; // 코스피/코스닥/선물/콜옵션 등
    private Long individualNetBuy;
    private Long foreignNetBuy;
    private Long institutionNetBuy;
}
