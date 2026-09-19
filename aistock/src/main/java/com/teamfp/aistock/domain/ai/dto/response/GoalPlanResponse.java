package com.teamfp.aistock.domain.ai.dto.response;

import com.teamfp.aistock.domain.ai.dto.request.GoalPlanRequest;
import java.time.LocalDateTime;

public record GoalPlanResponse(Long planId, GoalPlanRequest settings, long futureValue,
        long aggressiveFutureValue, boolean saved, LocalDateTime createdAt) {}
