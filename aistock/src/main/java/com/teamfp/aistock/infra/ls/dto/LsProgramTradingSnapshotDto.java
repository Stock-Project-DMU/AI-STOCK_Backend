package com.teamfp.aistock.infra.ls.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** LS증권 Open API 프로그램매매종합조회미니(t1640) — 시장 전체 프로그램매매 순매수 스냅샷. */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LsProgramTradingSnapshotDto {

    private Long offerValue; // 매도대금
    private Long bidValue; // 매수대금
    private Long netValue; // 순매수대금
}
