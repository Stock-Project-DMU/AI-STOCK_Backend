package com.teamfp.aistock.infra.marketdata.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 외부 시세 데이터 제공사 Open API 업종기간별추이(t1514) — 최근 며칠간 업종지수 일별 추이. */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class IndustryTrendDto {

    private String date;
    private Double indexValue;
    private Double changeRate;
    private Long foreignNetBuy; // frgsvolume
}
