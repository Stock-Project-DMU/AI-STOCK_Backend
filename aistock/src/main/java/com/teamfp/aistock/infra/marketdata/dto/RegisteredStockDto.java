package com.teamfp.aistock.infra.marketdata.dto;

/**
 * 등록 종목 목록(classpath {@code stocks.json}) 한 건. 홈 주요 종목 전체 보기·시뮬레이션 리밸런싱 후보처럼
 * "등록 종목 전체"를 대상으로 순위를 매길 때 real 모드에서 종목 범위를 정하는 기준이다.
 *
 * @param stockCode     종목코드(6자리)
 * @param stockName     종목명
 * @param market        KOSPI/KOSDAQ
 * @param listingShares 상장주식수(천주 단위, 시가총액 계산용 스냅샷). 없으면 null
 */
public record RegisteredStockDto(String stockCode, String stockName, String market, Long listingShares) {
}
