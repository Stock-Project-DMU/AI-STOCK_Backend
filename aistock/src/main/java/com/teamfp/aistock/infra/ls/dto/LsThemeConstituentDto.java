package com.teamfp.aistock.infra.ls.dto;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** LS증권 Open API 테마종목별시세조회(t1537) — 특정 테마에 속한 개별 종목의 시세. */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsThemeConstituentDto {

    private String stockCode;
    private String stockName;
    private Long price;
    private Long changeAmount;
    private Double changeRate;
    private Long volume;
}
