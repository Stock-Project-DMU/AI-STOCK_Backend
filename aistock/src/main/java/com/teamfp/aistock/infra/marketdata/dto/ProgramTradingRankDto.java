package com.teamfp.aistock.infra.marketdata.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 외부 시세 데이터 제공사 Open API 종목별프로그램매매동향(t1636) — 프로그램매매 순매수/순매도 상위 종목. */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ProgramTradingRankDto {

    private int rank;
    private String stockCode;
    private String stockName;
    private Long price;
    private Double changeRate;
    private Long netBuyValue; // svalue — 프로그램매매 순매수 대금
}
