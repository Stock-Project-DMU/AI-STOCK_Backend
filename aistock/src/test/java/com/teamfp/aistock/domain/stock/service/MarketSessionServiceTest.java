package com.teamfp.aistock.domain.stock.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.teamfp.aistock.domain.stock.dto.MarketSessionState;
import com.teamfp.aistock.domain.stock.dto.response.MarketStatusResponse;
import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;

class MarketSessionServiceTest {

    private static final Map<LocalDate, String> HOLIDAYS = Map.of(LocalDate.of(2026, 10, 9), "한글날");

    private static MarketSessionService at(String dateTime) {
        return at(dateTime, true);
    }

    private static MarketSessionService at(String dateTime, boolean enforce) {
        Clock clock = Clock.fixed(LocalDateTime.parse(dateTime).atZone(MarketSessionService.KST).toInstant(), MarketSessionService.KST);
        return new MarketSessionService(HOLIDAYS, clock, enforce);
    }

    @ParameterizedTest(name = "{0} → {1}")
    @DisplayName("영업일은 09:00 이전 장 시작 전, 09:00~15:30 정규장, 15:30부터 장 마감, 주말·휴장일은 휴장")
    @CsvSource({
            "2026-10-08T08:59:59, PRE_OPEN",
            "2026-10-08T09:00:00, OPEN",
            "2026-10-08T15:29:59, OPEN",
            "2026-10-08T15:30:00, CLOSED",
            "2026-10-09T10:00:00, HOLIDAY",
            "2026-10-11T10:00:00, HOLIDAY",
    })
    void states(String dateTime, MarketSessionState expected) {
        assertThat(at(dateTime).getStatus().state()).isEqualTo(expected);
    }

    @Test
    @DisplayName("일요일에는 마지막 거래일(한글날 휴장 전 목요일)과 다음 개장(월요일 09:00), 휴장 사유를 알려준다")
    void statusOnSunday() {
        MarketStatusResponse status = at("2026-10-11T21:00:00").getStatus();

        assertThat(status.isOpen()).isFalse();
        assertThat(status.lastTradingDay()).isEqualTo(LocalDate.of(2026, 10, 8));
        assertThat(status.nextOpen()).isEqualTo(LocalDateTime.of(2026, 10, 12, 9, 0));
        assertThat(status.holidayName()).isEqualTo("주말");
    }

    @Test
    @DisplayName("정규장 중에는 다음 개장 시각이 없고 주문할 수 있다")
    void statusDuringMarket() {
        MarketSessionService service = at("2026-10-12T10:00:00");

        assertThat(service.getStatus().nextOpen()).isNull();
        assertThat(service.isOpen()).isTrue();
        service.requireOpen();
    }

    @Test
    @DisplayName("장외에는 주문 진입부에서 MARKET_CLOSED를 던진다")
    void requireOpenOutsideMarket() {
        assertThatThrownBy(() -> at("2026-10-12T15:30:00").requireOpen())
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.MARKET_CLOSED);
    }

    @Test
    @DisplayName("실시간 전송은 정규장과 마감 후 60초까지만 허용한다")
    void broadcastWindow() {
        assertThat(at("2026-10-12T09:00:00").isBroadcastWindow()).isTrue();
        assertThat(at("2026-10-12T15:31:00").isBroadcastWindow()).isTrue();
        assertThat(at("2026-10-12T15:31:01").isBroadcastWindow()).isFalse();
        assertThat(at("2026-10-12T08:59:00").isBroadcastWindow()).isFalse();
        assertThat(at("2026-10-11T10:00:00").isBroadcastWindow()).isFalse();
    }

    @Test
    @DisplayName("장 운영 시간 제한을 끄면(통합 테스트용) 주말에도 정규장으로 본다")
    void enforceOff() {
        MarketSessionService service = at("2026-10-11T21:00:00", false);

        assertThat(service.isOpen()).isTrue();
        assertThat(service.isBroadcastWindow()).isTrue();
        service.requireOpen();
    }

    @Test
    @DisplayName("classpath의 krx_holidays.json을 읽어 휴장일을 반영한다")
    void loadsHolidayResource() {
        MarketSessionService service = new MarketSessionService(true);
        assertThat(service.getStatus().openTime()).isEqualTo(MarketSessionService.MARKET_OPEN_TIME);
        // 리소스 로딩이 실패하면 생성자에서 예외가 난다 — 여기까지 오면 휴장일 파일을 정상으로 읽은 것이다.
    }
}
