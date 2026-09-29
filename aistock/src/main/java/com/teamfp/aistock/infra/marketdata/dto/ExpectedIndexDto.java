package com.teamfp.aistock.infra.marketdata.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 외부 시세 데이터 제공사 Open API 예상지수(t1485) — 동시호가 시간대 업종지수 예상치. */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ExpectedIndexDto {

    private Double expectedIndexValue;
    private Double changeRate;
    private long upperLimitStockCount; // yupjo
    private long lowerLimitStockCount; // ydownjo
}
