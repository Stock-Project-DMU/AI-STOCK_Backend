package com.teamfp.aistock.infra.ls.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** LS증권 Open API 공매도일별추이(t1927) — 최근 며칠간 공매도 거래량/거래대금. */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LsShortSellingTrendDto {

    private String date;
    private Long shortSellingVolume; // gm_vo
    private Long shortSellingValue; // gm_va
    private Double shortSellingRatio; // gm_per — 전체 거래량 대비 공매도 비중(%)
}
