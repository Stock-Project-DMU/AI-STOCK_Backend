package com.teamfp.aistock.domain.ai.dto.request;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.List;
public record PlanningPreferencesRequest(
        @NotNull @Size(max = 100) List<@NotNull LocalDate> savedBriefingDates,
        @NotNull @Size(max = 10) List<@NotNull LocalDate> linkedBriefingDates,
        @NotNull @Size(max = 10) List<@NotNull @Positive Long> linkedGoalPlanIds) {}
