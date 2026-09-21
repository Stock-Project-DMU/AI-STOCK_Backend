package com.teamfp.aistock.infra.marketdata.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 외부 시세 데이터 제공사 Open API 관리/불성실/투자유의조회(t1404)·투자경고/매매정지/정리매매조회(t1405) 통합 결과. */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class StockRiskFlagDto {

    private String flagType; // "관리종목" 또는 "투자경고/매매정지"
    private String reasonCode;
    private String date;
}
