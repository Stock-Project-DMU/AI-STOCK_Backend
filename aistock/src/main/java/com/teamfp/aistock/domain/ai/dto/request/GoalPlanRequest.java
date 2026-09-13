package com.teamfp.aistock.domain.ai.dto.request;

import jakarta.validation.constraints.*;

public record GoalPlanRequest(@NotBlank @Pattern(regexp = "retirement|house") String goal,
        @Min(100000) @Max(5000000) long monthlyPayment,
        @Min(1) @Max(50) int years,
        @DecimalMin("0") @DecimalMax("12") double annualReturn, boolean aggressive) {}
