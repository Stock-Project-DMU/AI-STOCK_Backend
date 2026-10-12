package com.teamfp.aistock.domain.stock.service;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.teamfp.aistock.domain.stock.dto.MarketSessionState;
import com.teamfp.aistock.domain.stock.dto.response.MarketStatusResponse;
import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;

/**
 * 한국거래소 정규장(평일 09:00~15:30, 휴장일 제외) 운영 상태 — 실시세 전환 2단계.
 *
 * <p>시세는 local-market-data-generator가 LS증권에서 받아 정규장에만 바꾸고, 백엔드는 같은 규칙으로 장외 주문을 막고 장외에는
 * 실시간 시세를 보내지 않는다. 휴장일은 classpath의 {@code krx_holidays.json}(생성기 폴더의 같은 이름 파일과 내용이 같아야 한다)을
 * 읽는다. 서버 시간대와 무관하게 항상 한국 시간으로 판단한다.</p>
 *
 * <p>{@code market-session.enforce-trading-hours=false}면 항상 정규장으로 본다 — 주말·야간에도 실제 주문 흐름을 검증하는
 * 통합 테스트용이다. 운영 설정은 기본값(true)을 쓴다.</p>
 */
@Service
public class MarketSessionService {

    public static final ZoneId KST = ZoneId.of("Asia/Seoul");
    public static final LocalTime MARKET_OPEN_TIME = LocalTime.of(9, 0);
    public static final LocalTime MARKET_CLOSE_TIME = LocalTime.of(15, 30);
    static final String HOLIDAYS_RESOURCE = "krx_holidays.json";
    // 정규장이 15:30에 끝난 직후 생성기가 마지막 체결을 저장하는 시차가 있어, 마감 후 잠깐은 실시간 전송을 계속 허용한다.
    static final long BROADCAST_GRACE_SECONDS = 60;

    private final Map<LocalDate, String> holidays;
    private final Clock clock;
    private final boolean enforceTradingHours;

    @Autowired
    public MarketSessionService(@Value("${market-session.enforce-trading-hours:true}") boolean enforceTradingHours) {
        this(loadHolidays(), Clock.system(KST), enforceTradingHours);
    }

    // 테스트에서 시각·휴장일을 고정해 경계값을 검증할 수 있게 열어 둔다.
    MarketSessionService(Map<LocalDate, String> holidays, Clock clock, boolean enforceTradingHours) {
        this.holidays = holidays;
        this.clock = clock;
        this.enforceTradingHours = enforceTradingHours;
        warnIfHolidaysMissing();
    }

    // 휴장일 파일은 해마다 직접 채워야 한다 — 올해 항목이 하나도 없으면 설·추석 등에도 장이 열린 것으로 처리되므로 기동 때 알린다(2026-10-12 코드리뷰).
    private void warnIfHolidaysMissing() {
        int year = LocalDate.now(clock).getYear();
        if (holidays.keySet().stream().noneMatch(day -> day.getYear() == year)) {
            org.slf4j.LoggerFactory.getLogger(MarketSessionService.class).warn(
                    "{}에 {}년 휴장일이 없습니다 — 공휴일에도 정규장으로 처리됩니다. 생성기 폴더의 같은 파일과 함께 올해 휴장일을 추가하세요.",
                    HOLIDAYS_RESOURCE, year);
        }
    }

    public MarketStatusResponse getStatus() {
        LocalDateTime now = now();
        MarketSessionState state = state(now);
        return new MarketStatusResponse(
                state,
                isOpen(state),
                now,
                lastTradingDay(now, state),
                state == MarketSessionState.OPEN ? null : nextOpen(now, state),
                state == MarketSessionState.HOLIDAY ? holidayName(now.toLocalDate()) : null,
                MARKET_OPEN_TIME,
                MARKET_CLOSE_TIME);
    }

    /** 지금 주문할 수 있는지(정규장). enforce 설정이 꺼져 있으면 항상 true. */
    public boolean isOpen() {
        return isOpen(state(now()));
    }

    /** 장외면 MARKET_CLOSED — 주문 생성 진입부에서 호출한다(주문 취소는 막지 않는다). */
    public void requireOpen() {
        if (!isOpen()) {
            throw new CustomException(ErrorCode.MARKET_CLOSED);
        }
    }

    /** 실시간 시세를 보낼 시간인지 — 정규장과 마감 직후 BROADCAST_GRACE_SECONDS초. */
    public boolean isBroadcastWindow() {
        if (!enforceTradingHours) {
            return true;
        }
        LocalDateTime now = now();
        MarketSessionState state = state(now);
        if (state == MarketSessionState.OPEN) {
            return true;
        }
        return state == MarketSessionState.CLOSED
                && !now.toLocalTime().isAfter(MARKET_CLOSE_TIME.plusSeconds(BROADCAST_GRACE_SECONDS));
    }

    MarketSessionState state(LocalDateTime now) {
        if (!isTradingDay(now.toLocalDate())) {
            return MarketSessionState.HOLIDAY;
        }
        LocalTime time = now.toLocalTime();
        if (time.isBefore(MARKET_OPEN_TIME)) {
            return MarketSessionState.PRE_OPEN;
        }
        return time.isBefore(MARKET_CLOSE_TIME) ? MarketSessionState.OPEN : MarketSessionState.CLOSED;
    }

    private boolean isOpen(MarketSessionState state) {
        return !enforceTradingHours || state == MarketSessionState.OPEN;
    }

    private boolean isTradingDay(LocalDate day) {
        DayOfWeek dayOfWeek = day.getDayOfWeek();
        return dayOfWeek != DayOfWeek.SATURDAY && dayOfWeek != DayOfWeek.SUNDAY && !holidays.containsKey(day);
    }

    private String holidayName(LocalDate day) {
        DayOfWeek dayOfWeek = day.getDayOfWeek();
        if (dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY) {
            return "주말";
        }
        return holidays.get(day);
    }

    private LocalDate lastTradingDay(LocalDateTime now, MarketSessionState state) {
        if (state == MarketSessionState.OPEN || state == MarketSessionState.CLOSED) {
            return now.toLocalDate();
        }
        LocalDate candidate = now.toLocalDate().minusDays(1);
        while (!isTradingDay(candidate)) {
            candidate = candidate.minusDays(1);
        }
        return candidate;
    }

    private LocalDateTime nextOpen(LocalDateTime now, MarketSessionState state) {
        if (state == MarketSessionState.PRE_OPEN) {
            return now.toLocalDate().atTime(MARKET_OPEN_TIME);
        }
        LocalDate candidate = now.toLocalDate().plusDays(1);
        while (!isTradingDay(candidate)) {
            candidate = candidate.plusDays(1);
        }
        return candidate.atTime(MARKET_OPEN_TIME);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private static Map<LocalDate, String> loadHolidays() {
        try (InputStream input = new ClassPathResource(HOLIDAYS_RESOURCE).getInputStream()) {
            JsonNode root = new ObjectMapper().readTree(input).path("holidays");
            Map<LocalDate, String> holidays = new HashMap<>();
            root.properties().forEach(entry -> holidays.put(LocalDate.parse(entry.getKey()), entry.getValue().asText()));
            return Map.copyOf(holidays);
        } catch (IOException e) {
            throw new IllegalStateException("휴장일 파일(" + HOLIDAYS_RESOURCE + ")을 읽지 못했습니다.", e);
        }
    }
}
