package com.teamfp.aistock.domain.stock.dto.response;

/**
 * 배당 스케줄 재적재 결과(POST /api/admin/dividend/reload, feature/dividend). totalCount는 파일에서
 * 읽은 회차 수이며, 값이 바뀌지 않은 기존 회차는 createdCount·updatedCount 어디에도 세지 않는다.
 */
public record DividendScheduleReloadResponse(
        int totalCount,
        int createdCount,
        int updatedCount
) {
}
