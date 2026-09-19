package com.teamfp.aistock.domain.ai.service;
import com.teamfp.aistock.domain.ai.dto.request.PlanningPreferencesRequest;
import com.teamfp.aistock.domain.ai.repository.*;
import com.teamfp.aistock.global.exception.CustomException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
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
}
