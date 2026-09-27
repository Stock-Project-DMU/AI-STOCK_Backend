package com.teamfp.aistock.infra.marketdata.dto;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 외부 시세 데이터 제공사 Open API 해외지수조회API용(t3521) — 다우/나스닥 등 해외지수·환율·선물 현재가. */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OverseasIndexDto {

    private String symbol;
    private String name;
    private Double price;
    private Double changeAmount;
    private Double changeRate;
    private String date;
}
