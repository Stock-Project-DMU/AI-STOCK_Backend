package com.teamfp.aistock.global.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class DateUtilTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    // 2026-08-10은 월요일(평일)이고, 2026-08-08은 토요일이다 — 아래 테스트들이 요일 조건을
    // 명시적으로 검증하는 데 쓴다.
    private static ZonedDateTime weekdayAt(int hour, int minute) {
        return ZonedDateTime.of(2026, 8, 10, hour, minute, 0, 0, KST);
    }

    private static ZonedDateTime weekendAt(int hour, int minute) {
        return ZonedDateTime.of(2026, 8, 8, hour, minute, 0, 0, KST);
    }

    @Nested
    @DisplayName("동시호가 시간대 판단 (isCallAuctionTime)")
    class IsCallAuctionTime {

        @Test
        @DisplayName("평일 08:45(장 시작 동시호가)이면 true")
        void true_duringOpenAuction() {
            assertThat(DateUtil.isCallAuctionTime(weekdayAt(8, 45))).isTrue();
        }

        @Test
        @DisplayName("평일 15:25(장 마감 동시호가)이면 true")
        void true_duringCloseAuction() {
            assertThat(DateUtil.isCallAuctionTime(weekdayAt(15, 25))).isTrue();
        }

        @Test
        @DisplayName("평일 09:00 정각(동시호가 종료 경계)이면 false — 종료시각은 포함하지 않는다")
        void false_atOpenAuctionEndBoundary() {
            assertThat(DateUtil.isCallAuctionTime(weekdayAt(9, 0))).isFalse();
        }

        @Test
        @DisplayName("평일 10:00(정규장 중, 동시호가 아님)이면 false")
        void false_duringRegularSession() {
            assertThat(DateUtil.isCallAuctionTime(weekdayAt(10, 0))).isFalse();
        }

        @Test
        @DisplayName("주말은 시간대와 무관하게 항상 false")
        void false_onWeekend() {
            assertThat(DateUtil.isCallAuctionTime(weekendAt(8, 45))).isFalse();
        }
    }

    @Nested
    @DisplayName("시간외 거래 시간대 판단 (isAfterHoursTradingTime)")
    class IsAfterHoursTradingTime {

        @Test
        @DisplayName("평일 16:00(시간외 거래시간 중)이면 true")
        void true_duringAfterHours() {
            assertThat(DateUtil.isAfterHoursTradingTime(weekdayAt(16, 0))).isTrue();
        }

        @Test
        @DisplayName("평일 18:00 정각(종료 경계)이면 false — 종료시각은 포함하지 않는다")
        void false_atEndBoundary() {
            assertThat(DateUtil.isAfterHoursTradingTime(weekdayAt(18, 0))).isFalse();
        }

        @Test
        @DisplayName("평일 15:00(정규장 중, 시간외 아님)이면 false")
        void false_duringRegularSession() {
            assertThat(DateUtil.isAfterHoursTradingTime(weekdayAt(15, 0))).isFalse();
        }

        @Test
        @DisplayName("주말은 시간대와 무관하게 항상 false")
        void false_onWeekend() {
            assertThat(DateUtil.isAfterHoursTradingTime(weekendAt(16, 0))).isFalse();
        }
    }
}
