package com.teamfp.aistock.domain.ai.service;
import com.teamfp.aistock.domain.ai.dto.request.PlanningPreferencesRequest;
import com.teamfp.aistock.domain.ai.entity.GoalPlan;
import com.teamfp.aistock.domain.ai.entity.NewsBriefing;
import com.teamfp.aistock.domain.ai.entity.PlanningPreferences;
import com.teamfp.aistock.domain.ai.entity.Simulation;
import com.teamfp.aistock.domain.ai.repository.*;
import com.teamfp.aistock.global.exception.CustomException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlanningPreferencesServiceTest {
    private final PlanningPreferencesRepository preferences = mock(PlanningPreferencesRepository.class);
    private final NewsBriefingRepository news = mock(NewsBriefingRepository.class);
    private final GoalPlanRepository goals = mock(GoalPlanRepository.class);
    private final SimulationRepository simulations = mock(SimulationRepository.class);
    private final PlanningPreferencesService service = new PlanningPreferencesService(preferences, news, goals, simulations, new ObjectMapper());
    @Test void noSelectionsByDefault() {
        assertThat(service.getPreferences(2L).linkedGoalPlanIds()).isEmpty();
    }
    @Test void rejectsForeignGoalBeforeSaving() {
        assertThatThrownBy(() -> service.savePreferences(2L, new PlanningPreferencesRequest(List.of(), List.of(), List.of(7L), List.of())))
                .isInstanceOf(CustomException.class);
        verify(preferences, never()).save(any());
    }
    @Test void rejectsForeignBriefingBeforeSaving() {
        assertThatThrownBy(() -> service.savePreferences(2L, new PlanningPreferencesRequest(List.of(LocalDate.now()), List.of(), List.of(), List.of())))
                .isInstanceOf(CustomException.class);
        verify(preferences, never()).save(any());
    }
    @Test void persistsAndReadsEmptySelection() {
        var request = new PlanningPreferencesRequest(List.of(), List.of(), List.of(), List.of());
        assertThat(service.savePreferences(2L, request)).isEqualTo(request);
        verify(preferences).save(any());
    }
    @Test void listsSavedConnectionOptionsBeyondRecentHistory() {
        var stored = PlanningPreferences.from(2L);
        stored.updateSelections("{\"savedBriefingDates\":[\"2026-09-01\"],\"linkedBriefingDates\":[],\"linkedGoalPlanIds\":[]}");
        when(preferences.findById(2L)).thenReturn(Optional.of(stored));
        var goal = mock(GoalPlan.class);
        when(goal.getPlanId()).thenReturn(7L);
        when(goal.getGoal()).thenReturn("house");
        when(goal.getMonthlyPayment()).thenReturn(500000L);
        when(goal.getYears()).thenReturn(10);
        when(goals.findByUserIdAndSavedTrueOrderByCreatedAtDesc(2L)).thenReturn(List.of(goal));
        var briefing = mock(NewsBriefing.class);
        when(briefing.getBriefingDate()).thenReturn(LocalDate.of(2026, 9, 1));
        when(briefing.getOutletDomain()).thenReturn("bizwatch.co.kr");
        when(news.findByUserIdAndBriefingDateIn(2L, List.of(LocalDate.of(2026, 9, 1))))
            .thenReturn(List.of(briefing));

        var options = service.getConnectionOptions(2L);

        assertThat(options.goals()).hasSize(1);
        assertThat(options.goals().get(0).planId()).isEqualTo(7L);
        assertThat(options.briefings()).hasSize(1);
        assertThat(options.briefings().get(0).briefingDate()).isEqualTo(LocalDate.of(2026, 9, 1));
    }
    @Test void rejectsLinkedBriefingOutsideSavedList() {
        var date = LocalDate.of(2026, 9, 1);
        assertThatThrownBy(() -> service.savePreferences(2L,
            new PlanningPreferencesRequest(List.of(), List.of(date), List.of(), List.of())))
            .isInstanceOf(CustomException.class);
        verify(preferences, never()).save(any());
    }
    @Test void rejectsUnsavedGoalLink() {
        var goal = mock(GoalPlan.class);
        when(goals.findByPlanIdAndUserId(7L, 2L)).thenReturn(Optional.of(goal));
        assertThatThrownBy(() -> service.savePreferences(2L,
            new PlanningPreferencesRequest(List.of(), List.of(), List.of(7L), List.of())))
            .isInstanceOf(CustomException.class);
        verify(preferences, never()).save(any());
    }
    @Test void selectedSavedDataAppearsInAiContext() {
        var stored = PlanningPreferences.from(2L);
        stored.updateSelections("{\"savedBriefingDates\":[\"2026-09-01\"],\"linkedBriefingDates\":[\"2026-09-01\"],\"linkedGoalPlanIds\":[7]}");
        when(preferences.findById(2L)).thenReturn(Optional.of(stored));
        var goal = mock(GoalPlan.class);
        when(goal.isSaved()).thenReturn(true);
        when(goal.getGoal()).thenReturn("house");
        when(goal.getMonthlyPayment()).thenReturn(500000L);
        when(goal.getYears()).thenReturn(10);
        when(goal.getAnnualReturn()).thenReturn(5.0);
        when(goals.findByPlanIdAndUserId(7L, 2L)).thenReturn(Optional.of(goal));
        var briefing = mock(NewsBriefing.class);
        when(briefing.getContent()).thenReturn("사용자가 저장한 시장 요약");
        when(news.findByUserIdAndBriefingDate(2L, LocalDate.of(2026, 9, 1)))
            .thenReturn(Optional.of(briefing));

        String context = service.describeConnections(2L);

        assertThat(context).contains("house", "500000원", "2026-09-01", "사용자가 저장한 시장 요약");
    }
    @Test void rejectsMoreThanTwoLinkedGoals() {
        assertThatThrownBy(() -> service.savePreferences(2L,
            new PlanningPreferencesRequest(List.of(), List.of(), List.of(1L, 2L, 3L), List.of())))
            .isInstanceOf(CustomException.class);
        verify(preferences, never()).save(any());
    }
    @Test void rejectsMoreThanFiveTotalConnections() {
        var dates = List.of(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2),
            LocalDate.of(2026, 9, 3), LocalDate.of(2026, 9, 4));
        assertThatThrownBy(() -> service.savePreferences(2L,
            new PlanningPreferencesRequest(dates, dates, List.of(1L, 2L), List.of())))
            .isInstanceOf(CustomException.class);
        verify(preferences, never()).save(any());
    }
    @Test void acceptsFiveConnectionsIncludingTwoGoals() {
        var dates = List.of(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2), LocalDate.of(2026, 9, 3));
        for (var date : dates) when(news.existsByUserIdAndBriefingDate(2L, date)).thenReturn(true);
        var goal = mock(GoalPlan.class);
        when(goal.isSaved()).thenReturn(true);
        when(goals.findByPlanIdAndUserId(1L, 2L)).thenReturn(Optional.of(goal));
        when(goals.findByPlanIdAndUserId(2L, 2L)).thenReturn(Optional.of(goal));
        var request = new PlanningPreferencesRequest(dates, dates, List.of(1L, 2L), List.of());

        assertThat(service.savePreferences(2L, request)).isEqualTo(request);
        verify(preferences).save(any());
    }

    // ===== 목표 도달 시뮬레이션 v2 연동(feature/goal-simulation-v2, 2026-10-03) =====

    @Test void storedSelectionsWithoutSimulationIdsReadAsEmpty() {
        var stored = PlanningPreferences.from(2L);
        stored.updateSelections("{\"savedBriefingDates\":[],\"linkedBriefingDates\":[],\"linkedGoalPlanIds\":[7]}");
        when(preferences.findById(2L)).thenReturn(Optional.of(stored));

        var read = service.getPreferences(2L);

        assertThat(read.linkedGoalPlanIds()).containsExactly(7L);
        assertThat(read.linkedSimulationIds()).isEmpty();
    }
    @Test void listsSavedSimulationsAsConnectionOptions() {
        var simulation = mock(Simulation.class);
        when(simulation.getSimulationId()).thenReturn(11L);
        when(simulation.getGoalText()).thenReturn("3년 안에 5천만원");
        when(simulation.getTargetAmount()).thenReturn(50_000_000L);
        when(simulation.getPeriodMonths()).thenReturn(36);
        when(simulation.getRebalancedReachDate()).thenReturn(LocalDate.of(2029, 1, 1));
        when(simulations.findAllByUserIdOrderByCreatedAtDesc(2L)).thenReturn(List.of(simulation));

        var options = service.getConnectionOptions(2L);

        assertThat(options.simulations()).hasSize(1);
        assertThat(options.simulations().get(0).simulationId()).isEqualTo(11L);
        assertThat(options.simulations().get(0).goalText()).isEqualTo("3년 안에 5천만원");
        assertThat(options.goals()).isEmpty();
    }
    @Test void rejectsForeignSimulationLink() {
        when(simulations.findByUserIdAndSimulationId(2L, 11L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.savePreferences(2L,
            new PlanningPreferencesRequest(List.of(), List.of(), List.of(), List.of(11L))))
            .isInstanceOf(CustomException.class);
        verify(preferences, never()).save(any());
    }
    @Test void acceptsOwnSimulationLink() {
        when(simulations.findByUserIdAndSimulationId(2L, 11L)).thenReturn(Optional.of(mock(Simulation.class)));
        var request = new PlanningPreferencesRequest(List.of(), List.of(), List.of(), List.of(11L));

        assertThat(service.savePreferences(2L, request)).isEqualTo(request);
        verify(preferences).save(any());
    }
    @Test void goalPlansAndSimulationsShareTheTwoGoalLimit() {
        assertThatThrownBy(() -> service.savePreferences(2L,
            new PlanningPreferencesRequest(List.of(), List.of(), List.of(1L), List.of(11L, 12L))))
            .isInstanceOf(CustomException.class);
        verify(preferences, never()).save(any());
    }
    @Test void linkedSimulationAppearsInAiContext() {
        var stored = PlanningPreferences.from(2L);
        stored.updateSelections("{\"savedBriefingDates\":[],\"linkedBriefingDates\":[],\"linkedGoalPlanIds\":[],\"linkedSimulationIds\":[11]}");
        when(preferences.findById(2L)).thenReturn(Optional.of(stored));
        var simulation = mock(Simulation.class);
        when(simulation.getGoalText()).thenReturn("3년 안에 5천만원");
        when(simulation.getTargetAmount()).thenReturn(50_000_000L);
        when(simulation.getPeriodMonths()).thenReturn(36);
        when(simulation.getStartAmount()).thenReturn(10_000_000L);
        when(simulation.getMonthlyContribution()).thenReturn(500_000L);
        when(simulation.getCurrentReachDate()).thenReturn(null);
        when(simulation.getRebalancedReachDate()).thenReturn(LocalDate.of(2029, 1, 1));
        when(simulation.getProjectionData()).thenReturn("""
            {"holdingsAmount":0,"cashAmount":10000000,"shortenedMonths":null,
             "current":{"monthlyGrowthRate":0,"cashWeight":100,"allocations":[],"excludedStockNames":[],"points":[],
                        "reachMonths":null,"reachDate":null,"achievableWithinPeriod":false},
             "rebalanced":{"monthlyGrowthRate":0.02,"cashWeight":20,
                        "allocations":[{"stockCode":"005930","stockName":"삼성전자","weight":80,"monthlyGrowthRate":0.02}],
                        "excludedStockNames":[],"points":[],"reachMonths":27,"reachDate":"2029-01-01","achievableWithinPeriod":true}}
            """);
        when(simulations.findByUserIdAndSimulationId(2L, 11L)).thenReturn(Optional.of(simulation));

        String context = service.describeConnections(2L);

        assertThat(context).contains("목표 도달 시뮬레이션", "3년 안에 5천만원", "50000000원", "기한 36개월",
            "30년 내 미도달", "2029-01-01", "삼성전자 80.0%", "예수금 20.0%");
    }
}
