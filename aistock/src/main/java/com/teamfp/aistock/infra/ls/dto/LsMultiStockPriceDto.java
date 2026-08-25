package com.teamfp.aistock.infra.ls.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** LS증권 Open API API용주식멀티현재가조회(t8407) — 여러 종목의 현재가를 한번에. */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LsMultiStockPriceDto {

    private String stockCode;
    private String stockName;
    private Long price;
    private Long changeAmount;
    private Double changeRate;
    private Long volume;
}
