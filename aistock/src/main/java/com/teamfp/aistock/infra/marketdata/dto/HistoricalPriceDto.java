package com.teamfp.aistock.infra.marketdata.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 외부 시세 데이터 제공사 Open API 기간별주가(t1305) — 날짜별 시세+시가총액+외국인/개인 순매수. */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class HistoricalPriceDto {

    private String date;
    private Long open;
    private Long high;
    private Long low;
    private Long close;
    private Double changeRate;
    private Long volume;
    private Long marketCap;
    private Long foreignNetBuy;
    private Long individualNetBuy;
}
