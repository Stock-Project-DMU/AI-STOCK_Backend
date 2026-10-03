package com.teamfp.aistock.domain.ai.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 포트폴리오 하나(현재 보유 유지 / 리밸런싱)의 목표 도달 예측 결과. 화면의 왼쪽·오른쪽 차트가
 * 각각 이 객체 하나씩을 그린다.
 *
 * @param monthlyGrowthRate      종목별 보수적 월 성장률을 비중으로 가중평균한 포트폴리오 월 성장률(예수금은 0%)
 * @param cashWeight             예수금(현금) 비중(%)
 * @param allocations            종목별 비중·월 성장률
 * @param excludedStockNames     리밸런싱 추천에 들어왔지만 시세 이력이 12개월 미만이라 빠진 종목명(빠진 비중은 예수금으로 옮김).
 *                               현재 보유 포트폴리오에서는 항상 빈 목록
 * @param points                 월별 예상 평가금액 곡선(month 0 = 이번 달 1일)
 * @param reachMonths            목표 금액에 처음 도달하는 개월 수. 최대 계산 기간(30년) 안에 못 미치면 null
 * @param reachDate              reachMonths에 해당하는 날짜(매월 1일). 도달 못 하면 null
 * @param achievableWithinPeriod 목표 기한이 있을 때 기한 안에 도달하는지 여부. 기한이 없으면 null
 */
public record PortfolioProjectionDto(
        double monthlyGrowthRate,
        double cashWeight,
        List<PortfolioAllocationDto> allocations,
        List<String> excludedStockNames,
        List<ScenarioPointDto> points,
        Integer reachMonths,
        LocalDate reachDate,
        Boolean achievableWithinPeriod
) {
}
