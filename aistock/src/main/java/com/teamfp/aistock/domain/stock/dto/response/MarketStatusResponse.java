package com.teamfp.aistock.domain.stock.dto.response;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import com.teamfp.aistock.domain.stock.dto.MarketSessionState;

/**
 * 장 운영 상태 응답(실시세 전환 2단계) — 화면이 "장중/장 마감/휴장"을 표시하고 장외에는 주문 버튼을 막는 데 쓴다.
 *
 * @param isOpen         정규장이면 true(주문 가능)
 * @param now            서버 기준 한국 시간
 * @param lastTradingDay 지금 보이는 시세의 거래일(장 시작 전·휴장일이면 직전 영업일)
 * @param nextOpen       다음 정규장 시작 시각. 정규장 중이면 null
 * @param holidayName    휴장 사유("주말", "한글날" 등). 휴장이 아니면 null
 */
public record MarketStatusResponse(
        MarketSessionState state,
        boolean isOpen,
        LocalDateTime now,
        LocalDate lastTradingDay,
        LocalDateTime nextOpen,
        String holidayName,
        LocalTime openTime,
        LocalTime closeTime
) {
}
