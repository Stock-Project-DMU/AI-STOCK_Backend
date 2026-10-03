package com.teamfp.aistock.domain.ai.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.teamfp.aistock.domain.ai.entity.NewsChatMessage;
import com.teamfp.aistock.domain.ai.entity.NewsChatSession;
import com.teamfp.aistock.domain.user.entity.Role;
import com.teamfp.aistock.domain.user.entity.User;
import com.teamfp.aistock.domain.user.repository.UserRepository;

import jakarta.persistence.EntityManager;

@SpringBootTest
@Transactional
class NewsChatPersistenceIntegrationTest {
    @Autowired UserRepository users;
    @Autowired NewsChatSessionRepository sessions;
    @Autowired NewsChatMessageRepository messages;
    @Autowired EntityManager entityManager;

    @Test
    void restoresMessagesForOnlyTheSelectedOutletAndTime() {
        User user = users.save(User.builder()
                .loginId("news-chat-persistence-" + System.nanoTime())
                .name("뉴스 채팅 테스트")
                .role(Role.USER).isActive(true).build());
        NewsChatSession morning = sessions.saveAndFlush(NewsChatSession.builder()
                .user(user).settingKey("hankyung.com|07:00")
                .outletDomain("hankyung.com").deliveryTime(LocalTime.of(7, 0)).build());
        NewsChatSession evening = sessions.saveAndFlush(NewsChatSession.builder()
                .user(user).settingKey("hankyung.com|18:00")
                .outletDomain("hankyung.com").deliveryTime(LocalTime.of(18, 0)).build());
        messages.saveAndFlush(NewsChatMessage.builder()
                .session(morning).role("ASSISTANT").content("오전 답변")
                .sourcesJson("[]").searchedAt("2026-09-30T07:00:00.123456789+09:00").build());
        messages.saveAndFlush(NewsChatMessage.builder()
                .session(evening).role("USER").content("저녁 질문")
                .sourcesJson("[]").build());

        entityManager.clear();

        assertThat(sessions.findByUserAndSettingKey(user.getUserId(), "hankyung.com|07:00"))
                .get().extracting(NewsChatSession::getSessionId).isEqualTo(morning.getSessionId());
        assertThat(messages.findAllBySession(morning.getSessionId()))
                .extracting(NewsChatMessage::getContent).containsExactly("오전 답변");
        assertThat(messages.findAllBySession(evening.getSessionId()))
                .extracting(NewsChatMessage::getContent).containsExactly("저녁 질문");
        assertThat(messages.findAllBySession(morning.getSessionId()).getFirst().getSourcesJson())
                .isEqualTo("[]");
    }
}
