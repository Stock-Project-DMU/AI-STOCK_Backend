package com.teamfp.aistock.infra.ls.dto;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * LS증권 WebSocket 실시간 체결(tick) 원문 메시지를 파싱한 결과.
 * StockBroadcastService(4주차)가 이 값을 StockPriceDto로 변환해 Redis 캐싱·STOMP 브로드캐스팅에 사용한다.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LsTickData {

    private String stockCode;        // 종목코드
    private String stockName;        // 종목명
    private long currentPrice;       // 체결가
    private double changeRate;       // 전일 종가 대비 등락률(%)
    private long volume;             // 당일 누적 거래량
    private LocalDateTime tradedAt;  // 체결 시각
}
