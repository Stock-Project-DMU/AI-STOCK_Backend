package com.teamfp.aistock.domain.ai.service;

import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.teamfp.aistock.domain.ai.entity.NewsBriefingSetting;
import com.teamfp.aistock.domain.ai.repository.NewsBriefingRepository;
import com.teamfp.aistock.domain.ai.repository.NewsBriefingSettingRepository;
import com.teamfp.aistock.domain.notification.service.NotificationService;
import com.teamfp.aistock.domain.user.repository.UserRepository;
import com.teamfp.aistock.infra.gemini.GeminiApiClient;
import com.teamfp.aistock.infra.naver.NaverNewsApiClient;

import tools.jackson.databind.ObjectMapper;

class AiNewsServiceScheduleTest {
    private final NewsBriefingSettingRepository settings = mock(NewsBriefingSettingRepository.class);
    private final AiNewsService generator = mock(AiNewsService.class);
    private final AiNewsService service = new AiNewsService(settings, mock(NewsBriefingRepository.class),
            mock(UserRepository.class), mock(NaverNewsApiClient.class), mock(GeminiApiClient.class),
            mock(NotificationService.class), mock(ObjectMapper.class));

    @Test
    void generatesOnlyAfterSavedTimeAndClaimsOncePerDay() {
        ReflectionTestUtils.setField(service, "self", generator);
        var setting = NewsBriefingSetting.builder().outletDomain("hankyung.com")
                .briefingTime(LocalTime.of(9, 30)).build();
        ReflectionTestUtils.setField(setting, "settingId", 7L);
        when(settings.findDueSettings(any(), any())).thenAnswer(invocation -> {
            LocalTime now = invocation.getArgument(0);
            return now.isBefore(setting.getBriefingTime()) ? List.of() : List.of(setting);
        });
        LocalDate today = LocalDate.of(2026, 9, 26);

        service.generateDueBriefings(LocalDateTime.of(today, LocalTime.of(9, 29)));
        verify(settings, never()).claimBriefingAttempt(anyLong(), any(), any());

        when(settings.claimBriefingAttempt(eq(7L), eq(today), any())).thenReturn(1, 0);
        service.generateDueBriefings(LocalDateTime.of(today, LocalTime.of(9, 30)));
        service.generateDueBriefings(LocalDateTime.of(today, LocalTime.of(9, 31)));

        verify(generator, times(1)).generateBriefingForUser(setting, today);
    }
}
