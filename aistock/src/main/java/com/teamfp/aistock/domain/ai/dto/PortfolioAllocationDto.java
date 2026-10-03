package com.teamfp.aistock.domain.ai.dto;

/**
 * 시뮬레이션 포트폴리오의 종목 1건. weight는 포트폴리오 전체(예수금 포함) 대비 비중(%)이고,
 * monthlyGrowthRate는 그 종목의 보수적 월 성장률(최근 3년 월봉 평균 수익률에 할인 적용,
 * ScenarioCalculator.conservativeMonthlyRate() 참고)을 소수로 표현한 값이다(월 1%면 0.01).
 * 리밸런싱 추천을 서버가 검증하기 전 단계(RebalancePlanValidator 입력)에서는 stockName과
 * monthlyGrowthRate가 아직 채워지지 않아 null/0일 수 있다.
 */
public record PortfolioAllocationDto(
        String stockCode,
        String stockName,
        double weight,
        double monthlyGrowthRate
) {
}
