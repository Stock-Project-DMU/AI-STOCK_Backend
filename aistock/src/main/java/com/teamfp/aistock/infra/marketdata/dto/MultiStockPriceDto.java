package com.teamfp.aistock.infra.marketdata.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 외부 시세 데이터 제공사 Open API API용주식멀티현재가조회(t8407) — 여러 종목의 현재가를 한번에. */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class MultiStockPriceDto {

    private String stockCode;
    private String stockName;
    private Long price;
    private Long changeAmount;
    private Double changeRate;
    private Long volume;
    // 누적 거래대금(백만원) — 로컬 데이터에 거래대금이 없어 MarketDataApiClient가 현재가 × 거래량으로 근사한다.
    private Long tradingValue;
}
