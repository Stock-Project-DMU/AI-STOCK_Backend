package com.teamfp.aistock.infra.marketdata.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 외부 시세 데이터 제공사 Open API ETF구성종목조회(t1904) — ETF를 구성하는 개별 종목/채권. */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class EtfConstituentDto {

    private String stockCode;
    private String stockName;
    private Long price;
    private Double changeRate;
    private Double weight; // ETF 전체 대비 이 구성종목의 비중(%)
}
