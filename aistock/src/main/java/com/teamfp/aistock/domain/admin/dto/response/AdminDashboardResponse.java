package com.teamfp.aistock.domain.admin.dto.response;

import java.util.List;

/**
 * 관리자 대시보드 — 총 사용자·온라인 사용자·거래량·최근거래 요약 응답 DTO(NAMING.md 8-14).
 * 단일 Entity에서 뽑아내는 값이 아니라 UserRepository/OrderRepository/RedisOnlineStatusService
 * 세 곳의 집계 결과를 조합한 값이라, Entity를 받는 from() 대신 값 자체를 받는 of() 정적 팩토리를
 * 쓴다(AdminUserDetailResponse.of()와 동일한 패턴).
 */
public record AdminDashboardResponse(
        long totalUserCount,
        long onlineUserCount,
        long totalTradeCount,
        long totalTradeAmount,
        List<RecentTradeResponse> recentTrades
) {

    public static AdminDashboardResponse of(
            long totalUserCount,
            long onlineUserCount,
            long totalTradeCount,
            long totalTradeAmount,
            List<RecentTradeResponse> recentTrades
    ) {
        return new AdminDashboardResponse(totalUserCount, onlineUserCount, totalTradeCount, totalTradeAmount, recentTrades);
    }
}
