package com.teamfp.aistock.infra.ls.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** LS증권 Open API 업종기간별추이(t1514) — 최근 며칠간 업종지수 일별 추이. */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LsIndustryTrendDto {

    private String date;
    private Double indexValue;
    private Double changeRate;
    private Long foreignNetBuy; // frgsvolume
}
