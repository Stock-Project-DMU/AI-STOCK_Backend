package com.teamfp.aistock.domain.stock.dto.response;

import java.util.List;

import com.teamfp.aistock.infra.dart.dto.DartDividendResponse;

/**
 * 종목 리서치 "배당" 탭(fix/stock-detail-review) — 이전의 "배당 공시"(DART 원본 행)와 "배당"(회차별 일정) 두 탭을 합쳤다.
 *
 * @param annual    연간 배당 지표(DART 사업보고서 배당에 관한 사항). 공시가 없으면(ETF 등) null
 * @param schedules 회차별 배당 일정(local-market-data-generator dividends.json → DB, {@code GET /api/dividends/schedule}과 같은 값)
 */
public record StockDividendResearchResponse(
        DartDividendResponse annual,
        List<DividendScheduleResponse> schedules
) {
}
