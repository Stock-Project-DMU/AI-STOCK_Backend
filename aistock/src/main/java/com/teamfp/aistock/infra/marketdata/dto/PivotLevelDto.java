package com.teamfp.aistock.infra.marketdata.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 외부 시세 데이터 제공사 Open API 주식피봇/디마크조회(t1105) — 전일 시고저 기준 피봇 지지·저항선. */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PivotLevelDto {

    private String stockCode;
    private Long pivot;
    private Long resistance1;
    private Long support1;
    private Long resistance2;
    private Long support2;
}
