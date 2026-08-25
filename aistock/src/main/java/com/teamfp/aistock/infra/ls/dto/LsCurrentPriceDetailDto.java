package com.teamfp.aistock.infra.ls.dto;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * LS증권 Open API 현재가(시세)조회(t1102) 응답을 담는 DTO.
 *
 * {@code domain.stock.dto.StockPriceDto}와 일부러 분리했다 — StockPriceDto는 LS WebSocket
 * 체결 tick이 Redis({@code stock:price:{stockCode}}, TTL 5초)에 저장되는 "실시간 시세 캐시"
 * 형태를 나타내는 타입이라, PER·PBR·52주 최고/최저처럼 시세와 무관한 "기업정보+시세 종합판"
 * 성격의 필드를 여기에 얹으면 WebSocket tick 캐시 용도와 REST 종합조회 용도가 한 타입에
 * 섞여버린다. get_current_price 도구는 WebSocket 캐시가 아니라 이 REST 조회를 직접 쓰므로
 * (LsMarketDataApiClient 클래스 주석 참고) 전용 타입을 둔다.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LsCurrentPriceDetailDto {

    private String stockCode;        // 종목코드
    private String stockName;        // 종목명
    private long currentPrice;       // 현재가
    private long changeAmount;       // 전일 종가 대비 등락 금액(음수면 하락)
    private double changeRate;       // 전일 종가 대비 등락률(%)
    private long volume;             // 당일 누적 거래량
    private Double per;              // 주가수익비율(PER)
    private Double pbr;              // 주가순자산비율(PBR)
    private Long high52w;            // 52주 최고가
    private String high52wDate;      // 52주 최고가 기록일(YYYYMMDD)
    private Long low52w;             // 52주 최저가
    private String low52wDate;       // 52주 최저가 기록일(YYYYMMDD)
    private Long listingShares;      // 상장주식수(천주)
    private Double foreignExhaustionRate; // 외국인 보유한도 소진율(%)
    private LocalDateTime updatedAt; // 조회 시각
}
