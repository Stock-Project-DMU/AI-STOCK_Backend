package com.teamfp.aistock.global.util;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 국내 증권시장 세션(동시호가·시간외) 시간대 판단 유틸.
 * LS증권 API 중 "그 시간대에만 의미 있는" 도구(예상지수, 예상체결가 등)를
 * 호출하기 전에, 지금이 실제로 그 시간대인지 먼저 확인하는 용도로 쓴다.
 * 서버 시간대 설정과 무관하게 항상 한국 표준시(Asia/Seoul) 기준으로 판단한다.
 */
public class DateUtil {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    // 장 시작 동시호가: 08:30 ~ 09:00
    private static final LocalTime OPEN_CALL_AUCTION_START = LocalTime.of(8, 30);
    private static final LocalTime OPEN_CALL_AUCTION_END = LocalTime.of(9, 0);

    // 장 마감 동시호가: 15:20 ~ 15:30
    private static final LocalTime CLOSE_CALL_AUCTION_START = LocalTime.of(15, 20);
    private static final LocalTime CLOSE_CALL_AUCTION_END = LocalTime.of(15, 30);

    // 시간외 거래(종가매매+단일가매매): 15:30 ~ 18:00
    private static final LocalTime AFTER_HOURS_START = LocalTime.of(15, 30);
    private static final LocalTime AFTER_HOURS_END = LocalTime.of(18, 0);

    private DateUtil() {
    }

    /**
     * 지금이 동시호가(장 시작 전 08:30~09:00 또는 장 마감 전 15:20~15:30) 시간대인지 확인한다.
     * 주말에는 장이 열리지 않으므로 항상 false.
     */
    public static boolean isCallAuctionTime() {
        return isCallAuctionTime(ZonedDateTime.now(KST));
    }

    /**
     * 지금이 시간외 거래(15:30~18:00) 시간대인지 확인한다.
     * 주말에는 장이 열리지 않으므로 항상 false.
     */
    public static boolean isAfterHoursTradingTime() {
        return isAfterHoursTradingTime(ZonedDateTime.now(KST));
    }

    // 테스트에서 특정 시각을 주입해 검증할 수 있도록 기준 시각을 인자로 받는 오버로드를
    // package-private으로 열어둔다 — ZonedDateTime.now()를 직접 목킹할 수 없어 이렇게 분리했다.
    static boolean isCallAuctionTime(ZonedDateTime now) {
        if (isWeekend(now)) {
            return false;
        }
        LocalTime time = now.toLocalTime();
        boolean inOpenAuction = !time.isBefore(OPEN_CALL_AUCTION_START) && time.isBefore(OPEN_CALL_AUCTION_END);
        boolean inCloseAuction = !time.isBefore(CLOSE_CALL_AUCTION_START) && time.isBefore(CLOSE_CALL_AUCTION_END);
        return inOpenAuction || inCloseAuction;
    }

    static boolean isAfterHoursTradingTime(ZonedDateTime now) {
        if (isWeekend(now)) {
            return false;
        }
        LocalTime time = now.toLocalTime();
        return !time.isBefore(AFTER_HOURS_START) && time.isBefore(AFTER_HOURS_END);
    }

    private static boolean isWeekend(ZonedDateTime dateTime) {
        DayOfWeek dayOfWeek = dateTime.getDayOfWeek();
        return dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY;
    }
}
