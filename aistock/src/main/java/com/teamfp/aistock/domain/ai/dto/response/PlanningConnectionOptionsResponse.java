package com.teamfp.aistock.domain.ai.dto.response;

import java.time.LocalDate;
import java.util.List;

public record PlanningConnectionOptionsResponse(
        List<GoalOption> goals,
        List<BriefingOption> briefings) {
    public record GoalOption(Long planId, String goal, long monthlyPayment, int years) {}
    public record BriefingOption(LocalDate briefingDate, String outletName) {}
}
