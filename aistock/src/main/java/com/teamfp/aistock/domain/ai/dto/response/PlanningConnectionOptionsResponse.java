package com.teamfp.aistock.domain.ai.dto.response;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record PlanningConnectionOptionsResponse(
        List<GoalOption> goals,
        List<SimulationOption> simulations,
        List<BriefingOption> briefings) {
    public record GoalOption(Long planId, String goal, long monthlyPayment, int years) {}
    // 목표 도달 시뮬레이션 v2에서 저장한 결과(feature/goal-simulation-v2, 2026-10-03)
    public record SimulationOption(Long simulationId, String goalText, long targetAmount, Integer periodMonths,
                                   LocalDate rebalancedReachDate, LocalDateTime createdAt) {}
    public record BriefingOption(LocalDate briefingDate, String outletName) {}
}
