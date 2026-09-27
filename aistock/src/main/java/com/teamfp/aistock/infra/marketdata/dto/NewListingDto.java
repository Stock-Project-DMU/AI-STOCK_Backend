package com.teamfp.aistock.infra.marketdata.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 외부 시세 데이터 제공사 Open API 신규상장종목조회(t1403). */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class NewListingDto {

    private String stockCode;
    private String stockName;
    private String listedDate;
    private Long price;
}
