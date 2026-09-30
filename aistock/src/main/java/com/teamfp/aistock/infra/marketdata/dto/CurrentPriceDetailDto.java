package com.teamfp.aistock.infra.marketdata.dto;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 외부 시세 데이터 제공사 Open API 현재가(시세)조회(t1102) 응답을 담는 DTO.
 *
 * {@code domain.stock.dto.StockPriceDto}와 일부러 분리했다 — StockPriceDto는 외부 시세 데이터 WebSocket
 * 체결 tick이 Redis({@code stock:price:{stockCode}}, TTL 5초)에 저장되는 "실시간 시세 캐시"
 * 형태를 나타내는 타입이라, PER·PBR·52주 최고/최저처럼 시세와 무관한 "기업정보+시세 종합판"
 * 성격의 필드를 여기에 얹으면 WebSocket tick 캐시 용도와 REST 종합조회 용도가 한 타입에
 * 섞여버린다. get_current_price 도구는 WebSocket 캐시가 아니라 이 REST 조회를 직접 쓰므로
 * (MarketDataApiClient 클래스 주석 참고) 전용 타입을 둔다.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CurrentPriceDetailDto {

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

    // market-data.mode=mock 전용 필드 — 실제 t1102 응답에는 없고 local-market-data-generator가
    // stocks.json의 로컬 메타데이터(시장구분/ETF 여부)를 market_data.json에 함께 적어 넣은 값이다.
    // real 모드(t1102 직접 파싱, parseCurrentPrice())에서는 채워지지 않고 항상 null/false로 남는다.
    // IndustryApiClient/HighItemApiClient/EtfApiClient의 mock 분기(순위·지수·ETF 시세)가
    // LocalMarketDataReader.getAllCurrentPrices()로 전체 종목을 읽은 뒤 이 필드로 시장을 나누거나
    // ETF 종목만 걸러낸다(순위·지수·ETF 시세 mock 지원 추가, 2026-09-21).
    private String market;  // "KOSPI" 또는 "KOSDAQ"
    private boolean etf;    // Lombok 게터는 isEtf() — market_data.json의 "etf" 키와 매칭시킨다
                             // (필드명을 isEtf로 두면 Jackson이 필드 기반 "isEtf"와 게터 기반
                             // "etf" 프로퍼티명을 다르게 인식해 충돌할 수 있어 피한다)

    // market-data.mode=mock 전용 — ETF 종목(etf=true)에만 값이 채워진다. local-market-data-generator가
    // stocks.json의 isEtf 메타데이터를 보고 ETF 종목에 한해 고정값 "K"(KRX)를 market_data.json에
    // 적어 넣는다. 실제 외부 시세 데이터 API(t1901)의 exchgubun 스펙과는 무관한 로컬 개발용 필드다
    // (ETF exchgubun 신규 필드 반영, #04, 2026-09-23).
    private String exchgubun; // ETF 거래소구분("K"=KRX) — ETF가 아닌 종목은 null
}
