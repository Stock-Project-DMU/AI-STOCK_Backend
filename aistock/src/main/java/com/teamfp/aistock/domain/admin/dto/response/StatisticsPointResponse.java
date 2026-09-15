package com.teamfp.aistock.domain.admin.dto.response;

import com.teamfp.aistock.global.util.StatisticsPointProjection;

/**
 * 관리자 기간별 통계 응답 DTO(ADMIN_API_BACKEND_HANDOFF.md 6.1). period는 interval에 따라
 * "2026-08-29"(DAY), "2026-35"(WEEK, ISO 연도-주차), "2026-08"(MONTH) 형태의 문자열이다.
 */
public record StatisticsPointResponse(String period, long value) {

    public static StatisticsPointResponse from(StatisticsPointProjection projection) {
        return new StatisticsPointResponse(projection.getPeriod(), projection.getValue());
    }
}
