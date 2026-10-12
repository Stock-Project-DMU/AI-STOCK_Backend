package com.teamfp.aistock.domain.stock.dto;

/**
 * 한국거래소 정규장 운영 상태(실시세 전환 2단계). local-market-data-generator의 market_session.py와 같은 규칙이다.
 */
public enum MarketSessionState {
    /** 영업일 09:00 이전 — 주문 불가, 직전 거래일 종가 표시 */
    PRE_OPEN,
    /** 영업일 09:00~15:30 — 주문·체결·실시간 시세 */
    OPEN,
    /** 영업일 15:30 이후 — 주문 불가, 오늘 종가 고정 */
    CLOSED,
    /** 주말·휴장일 — 주문 불가 */
    HOLIDAY
}
