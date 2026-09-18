package com.teamfp.aistock.domain.ai.service;
import com.teamfp.aistock.domain.ai.dto.request.GoalPlanRequest;
import com.teamfp.aistock.domain.ai.entity.GoalPlan;
import com.teamfp.aistock.domain.ai.repository.GoalPlanRepository;
import com.teamfp.aistock.global.exception.CustomException;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GoalPlanServiceTest {
    @Test void zeroInterestIsPrincipal() {
        assertThat(GoalPlanService.calculateFutureValue(100_000, 10, 0)).isEqualTo(12_000_000);
    }
    @Test void monthlyCompoundingMatchesIndependentFormula() {
        long expected = Math.round(500_000 * (Math.pow(1 + 0.05 / 12, 120) - 1) / (0.05 / 12));
        assertThat(GoalPlanService.calculateFutureValue(500_000, 10, 5)).isEqualTo(expected);
    }
    @Test void cannotSaveOrDeleteAnotherUsersPlan() {
        var repository = mock(GoalPlanRepository.class);
        when(repository.findByPlanIdAndUserId(7L, 2L)).thenReturn(Optional.empty());
        var service = new GoalPlanService(repository);
        assertThatThrownBy(() -> service.savePlan(2L, 7L)).isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> service.deletePlan(2L, 7L)).isInstanceOf(CustomException.class);
        verify(repository, never()).delete(any());
    }
    @Test void ownPlanCanBeSaved() {
        var repository = mock(GoalPlanRepository.class);
        var plan = GoalPlan.from(2L, new GoalPlanRequest("retirement", 100_000, 10, 5, true));
        when(repository.findByPlanIdAndUserId(7L, 2L)).thenReturn(Optional.of(plan));
        var result = new GoalPlanService(repository).savePlan(2L, 7L);
        assertThat(result.saved()).isTrue();
        assertThat(result.aggressiveFutureValue()).isGreaterThan(result.futureValue());
    }
}
