package com.teamfp.aistock.infra.marketdata.dto;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 외부 시세 데이터 제공사 Open API 재무순위종합(t3341) — 재무지표 기준(ROE/PER/PBR 등) 전체 종목 랭킹. */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FinancialRankingDto {

    private int rank;
    private String stockCode;
    private String stockName;
    private Double roe;
    private Double per;
    private Double pbr;
    private Double salesGrowthRate;
}
