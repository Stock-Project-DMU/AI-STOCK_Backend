package com.teamfp.aistock.domain.stock.dto;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Redis {@code stock:price:{stockCode}} 캐시(TTL 5초)에 JSON으로 저장되는 실시간 현재가 정보.
 *
 * LS증권 WebSocket으로 체결 tick이 들어올 때마다 이 DTO로 변환되어 캐시에 저장되고,
 * 이후 현재가 조회 API 응답이나 STOMP {@code /topic/stock/{stockCode}} 브로드캐스팅에
 * 그대로 재사용된다. Jackson 직렬화/역직렬화를 위해 기본 생성자와 전체 필드 생성자를 둔다.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockPriceDto {

    private String stockCode;       // 종목코드
    private String stockName;       // 종목명
    private long currentPrice;      // 현재가
    private long changeAmount;      // 전일 종가 대비 등락 금액 (음수면 하락)
    private double changeRate;      // 전일 종가 대비 등락률(%)
    private long volume;            // 당일 누적 거래량
    private LocalDateTime updatedAt; // 이 tick을 수신/저장한 시각 (캐시 신선도 판단용)
}
