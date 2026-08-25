package com.teamfp.aistock.infra.ls.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** LS증권 Open API 시간별예상체결가(t1486) — 동시호가 시간대 종목별 예상체결가 시계열(최근 몇 건만 사용). */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LsCallAuctionPriceDto {

    private String time;
    private Long price;
    private Double changeRate;
    private Long expectedVolume;
}
