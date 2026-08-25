package com.teamfp.aistock.infra.ls.dto;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** LS증권 Open API 재무순위종합(t3341) — 재무지표 기준(ROE/PER/PBR 등) 전체 종목 랭킹. */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsFinancialRankingDto {

    private int rank;
    private String stockCode;
    private String stockName;
    private Double roe;
    private Double per;
    private Double pbr;
    private Double salesGrowthRate;
}
