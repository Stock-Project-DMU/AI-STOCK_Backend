package com.teamfp.aistock.infra.ls.dto;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** LS증권 Open API 증시주변자금추이(t8428) — 고객예탁금·신용잔고 등 시장 대기자금 추이. */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsMarketLiquidityDto {

    private String date;
    // 고객예탁금(투자자예탁금) — 증권계좌에 넣어두고 아직 주식을 안 산 대기자금.
    private Long customerDepositAmount;
    // 신용융자 잔고 — 빚을 내서 주식을 산 금액의 잔액.
    private Long marginLoanAmount;
}
