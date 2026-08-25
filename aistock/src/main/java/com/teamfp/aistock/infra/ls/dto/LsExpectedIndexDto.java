package com.teamfp.aistock.infra.ls.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** LS증권 Open API 예상지수(t1485) — 동시호가 시간대 업종지수 예상치. */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LsExpectedIndexDto {

    private Double expectedIndexValue;
    private Double changeRate;
    private long upperLimitStockCount; // yupjo
    private long lowerLimitStockCount; // ydownjo
}
