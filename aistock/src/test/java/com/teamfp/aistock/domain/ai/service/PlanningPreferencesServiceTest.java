package com.teamfp.aistock.domain.ai.service;
import com.teamfp.aistock.domain.ai.dto.request.PlanningPreferencesRequest;
import com.teamfp.aistock.domain.ai.entity.GoalPlan;
import com.teamfp.aistock.domain.ai.entity.NewsBriefing;
import com.teamfp.aistock.domain.ai.entity.PlanningPreferences;
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
    private final PlanningPreferencesService service = new PlanningPreferencesService(preferences, news, goals, new ObjectMapper());
    @Test void noSelectionsByDefault() {
        assertThat(service.getPreferences(2L).linkedGoalPlanIds()).isEmpty();
    }
    @Test void rejectsForeignGoalBeforeSaving() {
        assertThatThrownBy(() -> service.savePreferences(2L, new PlanningPreferencesRequest(List.of(), List.of(), List.of(7L))))
                .isInstanceOf(CustomException.class);
        verify(preferences, never()).save(any());
    }
    @Test void rejectsForeignBriefingBeforeSaving() {
        assertThatThrownBy(() -> service.savePreferences(2L, new PlanningPreferencesRequest(List.of(LocalDate.now()), List.of(), List.of())))
                .isInstanceOf(CustomException.class);
        verify(preferences, never()).save(any());
    }
    @Test void persistsAndReadsEmptySelection() {
        var request = new PlanningPreferencesRequest(List.of(), List.of(), List.of());
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
            new PlanningPreferencesRequest(List.of(), List.of(date), List.of())))
            .isInstanceOf(CustomException.class);
        verify(preferences, never()).save(any());
    }
    @Test void rejectsUnsavedGoalLink() {
        var goal = mock(GoalPlan.class);
        when(goals.findByPlanIdAndUserId(7L, 2L)).thenReturn(Optional.of(goal));
        assertThatThrownBy(() -> service.savePreferences(2L,
            new PlanningPreferencesRequest(List.of(), List.of(), List.of(7L))))
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
            new PlanningPreferencesRequest(List.of(), List.of(), List.of(1L, 2L, 3L))))
            .isInstanceOf(CustomException.class);
        verify(preferences, never()).save(any());
    }
    @Test void rejectsMoreThanFiveTotalConnections() {
        var dates = List.of(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2),
            LocalDate.of(2026, 9, 3), LocalDate.of(2026, 9, 4));
        assertThatThrownBy(() -> service.savePreferences(2L,
            new PlanningPreferencesRequest(dates, dates, List.of(1L, 2L))))
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
        var request = new PlanningPreferencesRequest(dates, dates, List.of(1L, 2L));

        assertThat(service.savePreferences(2L, request)).isEqualTo(request);
        verify(preferences).save(any());
    }
}
