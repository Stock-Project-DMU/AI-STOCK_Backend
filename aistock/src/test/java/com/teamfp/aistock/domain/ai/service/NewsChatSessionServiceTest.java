package com.teamfp.aistock.domain.ai.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import tools.jackson.databind.ObjectMapper;

import com.teamfp.aistock.domain.ai.dto.request.NewsChatMessageRequest;
import com.teamfp.aistock.domain.ai.dto.request.NewsChatRequest;
import com.teamfp.aistock.domain.ai.dto.response.NewsChatResponse;
import com.teamfp.aistock.domain.ai.entity.NewsBriefing;
import com.teamfp.aistock.domain.ai.entity.NewsBriefingSetting;
import com.teamfp.aistock.domain.ai.entity.NewsChatSession;
import com.teamfp.aistock.domain.ai.repository.NewsBriefingRepository;
import com.teamfp.aistock.domain.ai.repository.NewsBriefingSettingRepository;
import com.teamfp.aistock.domain.ai.repository.NewsChatMessageRepository;
import com.teamfp.aistock.domain.ai.repository.NewsChatSessionRepository;
import com.teamfp.aistock.domain.user.repository.UserRepository;
import com.teamfp.aistock.global.exception.CustomException;

class NewsChatSessionServiceTest {
    private final NewsChatSessionRepository sessions = mock(NewsChatSessionRepository.class);
    private final NewsChatMessageRepository messages = mock(NewsChatMessageRepository.class);
    private final NewsBriefingSettingRepository settings = mock(NewsBriefingSettingRepository.class);
    private final NewsBriefingRepository briefings = mock(NewsBriefingRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final NewsChatService chat = mock(NewsChatService.class);
    private final NewsChatSessionService service = new NewsChatSessionService(
            sessions, messages, settings, briefings, users, chat, new ObjectMapper());

    private NewsChatSession session(long id, String domain, LocalTime time) {
        var value = NewsChatSession.builder().settingKey(domain + "|" + (time == null ? "legacy" : time))
                .outletDomain(domain).deliveryTime(time).build();
        ReflectionTestUtils.setField(value, "sessionId", id);
        return value;
    }

    @Test
    void syncReusesTheSameSettingAndSeparatesAnotherTimeAndLegacyBriefings() {
        var current = session(1, "hankyung.com", LocalTime.of(7, 0));
        var later = session(2, "hankyung.com", LocalTime.of(8, 0));
        var legacy = session(3, "hankyung.com", null);
        when(settings.findByUserId(7L)).thenReturn(Optional.of(
                NewsBriefingSetting.builder().outletDomain("hankyung.com").briefingTime(LocalTime.of(7, 0)).build()));
        when(briefings.findDistinctSettingsByUserId(7L)).thenReturn(List.of(
                new Object[]{"hankyung.com", LocalTime.of(7, 0)},
                new Object[]{"hankyung.com", LocalTime.of(8, 0)},
                new Object[]{"hankyung.com", null}));
        when(sessions.findByUserAndSettingKey(7L, "hankyung.com|07:00")).thenReturn(Optional.of(current));
        when(sessions.findByUserAndSettingKey(7L, "hankyung.com|08:00")).thenReturn(Optional.of(later));
        when(sessions.findByUserAndSettingKey(7L, "hankyung.com|legacy")).thenReturn(Optional.of(legacy));
        when(sessions.findAllByUser(7L)).thenReturn(List.of(current, later, legacy));

        var result = service.sync(7L);

        assertThat(result.currentSessionId()).isEqualTo(1L);
        assertThat(result.sessions()).extracting(item -> item.deliveryTime())
                .containsExactly(LocalTime.of(7, 0), LocalTime.of(8, 0), null);
        verify(sessions, never()).saveAndFlush(any());
    }

    @Test
    void oldSessionUsesItsOwnOutletAndRejectsBriefingFromAnotherSetting() {
        var selected = session(4, "hankyung.com", LocalTime.of(7, 0));
        when(sessions.findByUserAndSessionId(7L, 4L)).thenReturn(Optional.of(selected));
        when(messages.findRecentBySession(eq(4L), any())).thenReturn(List.of());
        var wrongTime = NewsBriefing.builder().outletDomain("hankyung.com")
                .briefingTime(LocalTime.of(8, 0)).briefingDate(LocalDate.of(2026, 9, 30))
                .content("요약").sourceLinksJson("[]").build();
        when(briefings.findByUserIdAndBriefingDate(7L, LocalDate.of(2026, 9, 30))).thenReturn(Optional.of(wrongTime));

        assertThatThrownBy(() -> service.send(7L, 4L,
                new NewsChatMessageRequest("질문", LocalDate.of(2026, 9, 30))))
                .isInstanceOf(CustomException.class);
        verifyNoInteractions(chat);

        when(chat.chatForOutlet(eq("hankyung.com"), any(NewsChatRequest.class)))
                .thenReturn(new NewsChatResponse("답변", List.of(), "2026-09-30T12:00:00+09:00"));
        when(messages.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        service.send(7L, 4L, new NewsChatMessageRequest("질문", null));
        verify(chat).chatForOutlet(eq("hankyung.com"), any(NewsChatRequest.class));
        verify(sessions).save(selected);
    }
}
