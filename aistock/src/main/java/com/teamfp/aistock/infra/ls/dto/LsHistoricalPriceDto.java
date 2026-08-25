package com.teamfp.aistock.infra.ls.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** LS증권 Open API 기간별주가(t1305) — 날짜별 시세+시가총액+외국인/개인 순매수. */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LsHistoricalPriceDto {

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
