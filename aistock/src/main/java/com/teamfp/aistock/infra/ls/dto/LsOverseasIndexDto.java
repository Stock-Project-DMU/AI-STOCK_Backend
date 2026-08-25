package com.teamfp.aistock.infra.ls.dto;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** LS증권 Open API 해외지수조회API용(t3521) — 다우/나스닥 등 해외지수·환율·선물 현재가. */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsOverseasIndexDto {

    private String symbol;
    private String name;
    private Double price;
    private Double changeAmount;
    private Double changeRate;
    private String date;
}
