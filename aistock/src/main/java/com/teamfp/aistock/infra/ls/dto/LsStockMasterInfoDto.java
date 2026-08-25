package com.teamfp.aistock.infra.ls.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** LS증권 Open API 주식종목조회API용(t8436) — 종목 기본정보(상하한가, 스팩여부 등). */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LsStockMasterInfoDto {

    private String stockCode;
    private String stockName;
    private Long upperLimitPrice;
    private Long lowerLimitPrice;
    private boolean isSpac; // spac_gubun == "Y"
}
