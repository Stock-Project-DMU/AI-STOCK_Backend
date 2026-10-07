package com.teamfp.aistock.infra.marketdata.dto;

/**
 * 등록 종목 목록(classpath {@code stocks.json}) 한 건 — 종목코드로 종목명·시장·상장주식수를 찾는 카탈로그(예: 배당 일정 종목명).
 *
 * @param stockCode     종목코드(6자리)
 * @param stockName     종목명
 * @param market        KOSPI/KOSDAQ
 * @param listingShares 상장주식수(천주 단위, 시가총액 계산용 스냅샷). 없으면 null
 */
public record RegisteredStockDto(String stockCode, String stockName, String market, Long listingShares) {
}
