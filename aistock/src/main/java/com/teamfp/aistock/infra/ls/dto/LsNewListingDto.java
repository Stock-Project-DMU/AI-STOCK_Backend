package com.teamfp.aistock.infra.ls.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** LS증권 Open API 신규상장종목조회(t1403). */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LsNewListingDto {

    private String stockCode;
    private String stockName;
    private String listedDate;
    private Long price;
}
