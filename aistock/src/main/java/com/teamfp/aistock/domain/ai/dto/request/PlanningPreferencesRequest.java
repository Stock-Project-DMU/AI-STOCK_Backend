package com.teamfp.aistock.domain.ai.dto.request;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.List;
/**
 * AI 상담 자료 연동 설정. linkedGoalPlanIds는 예전 적립식 목표(goal_plans), linkedSimulationIds는
 * 목표 도달 시뮬레이션 v2에서 저장한 결과(simulations)다 — 두 테이블의 ID가 겹칠 수 있어 목록을 나눈다
 * (feature/goal-simulation-v2, 2026-10-03). 목표 연동은 두 목록을 합쳐 최대 2개(서비스에서 검증).
 * linkedSimulationIds가 없는 예전 저장값·요청은 빈 목록으로 읽는다.
 */
public record PlanningPreferencesRequest(
        @NotNull @Size(max = 100) List<@NotNull LocalDate> savedBriefingDates,
        @NotNull @Size(max = 5) List<@NotNull LocalDate> linkedBriefingDates,
        @NotNull @Size(max = 2) List<@NotNull @Positive Long> linkedGoalPlanIds,
        @Size(max = 2) List<@NotNull @Positive Long> linkedSimulationIds) {
    public PlanningPreferencesRequest {
        linkedSimulationIds = linkedSimulationIds == null ? List.of() : linkedSimulationIds;
    }
}
