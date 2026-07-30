package com.teamfp.aistock.infra.ls.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * LS증권 WebSocket 실시간 호가 원문 메시지를 파싱한 결과.
 * StockBroadcastService(4주차)가 이 값을 HogaDto로 변환해 Redis 캐싱·STOMP 브로드캐스팅에 사용한다.
 * 인덱스 0이 가장 우선순위 높은 호가(매도는 최저가, 매수는 최고가)이며,
 * askPrices[i]·askVolumes[i], bidPrices[i]·bidVolumes[i]는 서로 같은 인덱스끼리 짝을 이룬다.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LsHogaData {

    private String stockCode;
    private List<Long> askPrices;
    private List<Long> askVolumes;
    private List<Long> bidPrices;
    private List<Long> bidVolumes;
}
