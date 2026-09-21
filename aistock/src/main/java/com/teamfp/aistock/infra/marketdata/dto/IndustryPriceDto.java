package com.teamfp.aistock.infra.marketdata.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 외부 시세 데이터 제공사 Open API 업종현재가(t1511) — 업종지수 현재가 스냅샷. */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class IndustryPriceDto {

    private String industryCode;
    private String industryName;
    private Double indexValue;
    private Double changeRate;
}
