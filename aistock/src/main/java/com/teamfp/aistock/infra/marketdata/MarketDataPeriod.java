package com.teamfp.aistock.infra.marketdata;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Function;

/**
 * 기간 인자(periodMonths)를 받는 시세 조회의 공통 규칙(fix/local-market-data-stable). 기간이 없으면 최근 몇 건만,
 * 기간이 있으면 그 개월 수(최대 24개월=2년) 안의 행을 모두 돌려준다 — 이전 외부 시세 데이터 클라이언트들이 TR 조회 범위로
 * 하던 일을 로컬 데이터(최신순, 날짜 yyyyMMdd)에 똑같이 적용한다.
 */
final class MarketDataPeriod {

    static final int MAX_PERIOD_MONTHS = 24;
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;

    private MarketDataPeriod() {
    }

    static boolean isLongPeriod(Integer periodMonths) {
        return periodMonths != null && periodMonths > 0;
    }

    /**
     * 최신순 목록에서 기간에 맞는 행만 남긴다. 기간이 없으면 앞에서 recentCount건, 있으면 오늘부터 periodMonths개월
     * (최대 {@value #MAX_PERIOD_MONTHS}) 이내 날짜의 행(최대 longCap건)을 돌려준다.
     */
    static <T> List<T> select(List<T> rowsNewestFirst, Function<T, String> dateOf, Integer periodMonths,
            int recentCount, int longCap) {
        if (!isLongPeriod(periodMonths)) {
            return rowsNewestFirst.size() > recentCount ? rowsNewestFirst.subList(0, recentCount) : rowsNewestFirst;
        }
        String from = LocalDate.now().minusMonths(Math.min(periodMonths, MAX_PERIOD_MONTHS)).format(DATE_FORMAT);
        List<T> inRange = rowsNewestFirst.stream()
                .filter(row -> dateOf.apply(row) == null || dateOf.apply(row).compareTo(from) >= 0)
                .toList();
        return inRange.size() > longCap ? inRange.subList(0, longCap) : inRange;
    }
}
