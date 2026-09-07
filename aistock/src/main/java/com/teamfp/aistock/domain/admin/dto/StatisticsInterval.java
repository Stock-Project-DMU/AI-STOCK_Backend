package com.teamfp.aistock.domain.admin.dto;

/**
 * 관리자 기간별 통계 집계 단위(ADMIN_API_BACKEND_HANDOFF.md 6.1). MySQL DATE_FORMAT()에 그대로
 * 넘길 수 있는 포맷 패턴을 값으로 갖는다 — WEEK는 %x-%v(ISO 연도-ISO 주차, 예: "2026-W35"는
 * 표기상 하이픈만 다르게 "2026-35"로 나간다는 점에 주의)로 연말/연초 경계에서 실제 달력 주와
 * 어긋나지 않는 ISO 8601 주차 기준을 쓴다(%Y 대신 %x, %U/%u 대신 %v).
 */
public enum StatisticsInterval {
    DAY("%Y-%m-%d"),
    WEEK("%x-%v"),
    MONTH("%Y-%m");

    private final String mysqlDateFormatPattern;

    StatisticsInterval(String mysqlDateFormatPattern) {
        this.mysqlDateFormatPattern = mysqlDateFormatPattern;
    }

    public String getMysqlDateFormatPattern() {
        return mysqlDateFormatPattern;
    }
}
