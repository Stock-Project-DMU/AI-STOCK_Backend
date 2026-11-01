package com.teamfp.aistock.domain.ai.service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import com.teamfp.aistock.domain.ai.dto.request.NewsChatMessageRequest;
import com.teamfp.aistock.domain.ai.dto.request.NewsChatRequest;
import com.teamfp.aistock.domain.ai.dto.response.NewsChatMessageResponse;
import com.teamfp.aistock.domain.ai.dto.response.NewsChatSessionResponse;
import com.teamfp.aistock.domain.ai.dto.response.NewsChatSessionsResponse;
import com.teamfp.aistock.domain.ai.entity.NewsBriefing;
import com.teamfp.aistock.domain.ai.entity.NewsChatMessage;
import com.teamfp.aistock.domain.ai.entity.NewsChatSession;
import com.teamfp.aistock.domain.ai.repository.NewsBriefingRepository;
import com.teamfp.aistock.domain.ai.repository.NewsBriefingSettingRepository;
import com.teamfp.aistock.domain.ai.repository.NewsChatMessageRepository;
import com.teamfp.aistock.domain.ai.repository.NewsChatSessionRepository;
import com.teamfp.aistock.domain.user.repository.UserRepository;
import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;
import com.teamfp.aistock.infra.naver.dto.NaverNewsSearchResponse.NaverNewsResult;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class NewsChatSessionService {
    private final NewsChatSessionRepository sessions;
    private final NewsChatMessageRepository messages;
    private final NewsBriefingSettingRepository settings;
    private final NewsBriefingRepository briefings;
    private final UserRepository users;
    private final NewsChatService chat;
    private final ObjectMapper mapper;

    @Transactional
    public NewsChatSessionsResponse sync(Long userId) {
        var currentSetting = settings.findByUserId(userId);
        String currentDomain = currentSetting.map(s -> s.getOutletDomain()).orElse(null);
        LocalTime currentTime = currentSetting.map(s -> s.getBriefingTime()).orElse(null);
        NewsChatSession current = ensure(userId, currentDomain, currentTime);
        // 지금 설정의 채팅은 예전에 삭제했더라도 다시 쓰므로 되살린다(대화 기록은 삭제 때 지워져 빈 채팅이다).
        current.restore();
        for (Object[] setting : briefings.findDistinctSettingsByUserId(userId)) {
            ensure(userId, (String) setting[0], (LocalTime) setting[1]);
        }
        sessions.flush();
        return new NewsChatSessionsResponse(current.getSessionId(),
                sessions.findAllByUser(userId).stream().map(NewsChatSessionResponse::from).toList());
    }

    @Transactional(readOnly = true)
    public List<NewsChatMessageResponse> getMessages(Long userId, Long sessionId) {
        requireSession(userId, sessionId);
        return messages.findAllBySession(sessionId).stream().map(this::toResponse).toList();
    }

    @Transactional
    public List<NewsChatMessageResponse> send(Long userId, Long sessionId, NewsChatMessageRequest request) {
        NewsChatSession session = requireSession(userId, sessionId);
        List<NewsChatMessage> recent = new ArrayList<>(messages.findRecentBySession(sessionId, PageRequest.of(0, 8)));
        Collections.reverse(recent);
        List<NewsChatRequest.Turn> history = new ArrayList<>();
        if (request.briefingDate() != null) {
            NewsBriefing briefing = requireBriefing(userId, request.briefingDate(), session);
            String context = briefing.getBriefingDate() + " " + NewsChatSessionResponse.from(session).outletName()
                    + " 시황 브리핑: " + briefing.getContent();
            history.add(new NewsChatRequest.Turn("ASSISTANT", truncate(context, 6000)));
        }
        int maxPrevious = 8 - history.size();
        recent.stream().skip(Math.max(0, recent.size() - maxPrevious))
                .forEach(message -> history.add(new NewsChatRequest.Turn(message.getRole(), truncate(message.getContent(), 6000))));
        var answer = chat.chatForOutlet(session.getOutletDomain(), new NewsChatRequest(request.content(), history));
        NewsChatMessage user = messages.saveAndFlush(NewsChatMessage.builder()
                .session(session).role("USER").content(request.content()).sourcesJson("[]").build());
        NewsChatMessage assistant = messages.saveAndFlush(NewsChatMessage.builder()
                .session(session).role("ASSISTANT").content(answer.content())
                .sourcesJson(serializeSources(answer.sources())).searchedAt(answer.searchedAt()).build());
        session.recordActivity();
        sessions.save(session);
        return List.of(toResponse(user), toResponse(assistant));
    }

    /**
     * 설정별 채팅 삭제(fix/site-polish) — AI 재무설계사 상담 삭제처럼 대화 기록을 지우고 목록에서 뺀다. 지금 받고 있는
     * 설정의 채팅이면 브리핑 설정도 함께 지운다(설정을 남겨두면 다음 브리핑 때 이 채팅이 다시 생긴다) — 이후 브리핑은
     * 오지 않고, 설정이 없을 때처럼 "전체 뉴스" 채팅이 지금 채팅이 된다. 이미 받은 브리핑 기록은 저장 목록 등에서
     * 날짜로 쓰고 있어 지우지 않는다.
     */
    @Transactional
    public void delete(Long userId, Long sessionId) {
        NewsChatSession session = requireSession(userId, sessionId);
        settings.findByUserId(userId)
                .filter(setting -> key(setting.getOutletDomain(), setting.getBriefingTime()).equals(session.getSettingKey()))
                .ifPresent(settings::delete);
        messages.deleteAllBySession(sessionId);
        session.delete();
    }

    private NewsChatSession ensure(Long userId, String domain, LocalTime time) {
        String settingKey = key(domain, time);
        return sessions.findByUserAndSettingKey(userId, settingKey)
                .orElseGet(() -> sessions.saveAndFlush(NewsChatSession.builder()
                        .user(users.getReferenceById(userId)).settingKey(settingKey)
                        .outletDomain(domain).deliveryTime(time).build()));
    }

    private String key(String domain, LocalTime time) {
        if (domain == null) return "general";
        return domain + "|" + (time == null ? "legacy" : time.toString());
    }

    private NewsChatSession requireSession(Long userId, Long sessionId) {
        return sessions.findByUserAndSessionId(userId, sessionId)
                .orElseThrow(() -> new CustomException(ErrorCode.NEWS_CHAT_SESSION_NOT_FOUND));
    }

    private NewsBriefing requireBriefing(Long userId, LocalDate date, NewsChatSession session) {
        // 세션(설정)마다 그날 브리핑이 따로 있다(fix/site-polish) — 이 세션의 언론사·시각과 같은 브리핑을 찾는다.
        // 그날 브리핑은 있는데 이 세션 것이 아니면 예전처럼 INVALID_INPUT.
        return briefings.findByUserIdAndBriefingDateAndSetting(userId, date, session.getOutletDomain(), session.getDeliveryTime())
                .orElseThrow(() -> new CustomException(briefings.existsByUserIdAndBriefingDate(userId, date)
                        ? ErrorCode.INVALID_INPUT : ErrorCode.NEWS_BRIEFING_NOT_FOUND));
    }

    private String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private String serializeSources(List<NaverNewsResult> sources) {
        try {
            return mapper.writeValueAsString(sources);
        } catch (JacksonException cause) {
            throw new CustomException(ErrorCode.NEWS_CHAT_DATA_PARSE_ERROR, cause);
        }
    }

    private NewsChatMessageResponse toResponse(NewsChatMessage message) {
        try {
            NaverNewsResult[] sources = mapper.readValue(message.getSourcesJson(), NaverNewsResult[].class);
            return new NewsChatMessageResponse(message.getMessageId(), message.getRole(), message.getContent(),
                    sources == null ? List.of() : List.of(sources), message.getSearchedAt(), message.getCreatedAt());
        } catch (JacksonException cause) {
            throw new CustomException(ErrorCode.NEWS_CHAT_DATA_PARSE_ERROR, cause);
        }
    }
}
