package com.teamfp.aistock.manualcheck;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.ObjectMapper;

import com.teamfp.aistock.domain.ai.entity.NewsBriefing;
import com.teamfp.aistock.domain.ai.entity.NewsBriefingSetting;
import com.teamfp.aistock.domain.ai.repository.NewsBriefingRepository;
import com.teamfp.aistock.domain.ai.repository.NewsBriefingSettingRepository;
import com.teamfp.aistock.domain.ai.service.AiNewsService;
import com.teamfp.aistock.domain.notification.service.NotificationService;
import com.teamfp.aistock.domain.user.entity.Role;
import com.teamfp.aistock.domain.user.entity.User;
import com.teamfp.aistock.domain.user.repository.UserRepository;
import com.teamfp.aistock.infra.gemini.GeminiApiClient;
import com.teamfp.aistock.infra.naver.NaverNewsApiClient;

/**
 * 일회성 수동 라이브 점검용 — feature/ai-news(맞춤형 뉴스 브리핑)가 실제 네이버 뉴스 API +
 * Gemini로 어떤 브리핑을 만들어내는지 확인한다. DB/Redis 없이도 실행 가능하도록
 * generateBriefingForUser()에 필요한 Repository/NotificationService만 목(mock)으로 채운다.
 * CI 자동 실행 금지.
 *
 * 사용법: OUTLET_DOMAIN만 바꿔서 다른 언론사로도 재실행 가능.
 * RUN_LIVE_GEMINI_TEST=true GEMINI_API_KEY=... NAVER_CLIENT_ID=... NAVER_CLIENT_SECRET=...
 * 환경변수를 채운 뒤 실행한다.
 */
class LiveNewsBriefingManualCheck {

    private static final String OUTLET_DOMAIN = "ajunews.com"; // 아주경제 — SK하이닉스 지어내기가 3회 재현된 언론사, 검증 로직 재도입 후 재확인용

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_LIVE_GEMINI_TEST", matches = "true")
    void checkRealBriefing() {
        NewsBriefingSettingRepository settingRepository = mock(NewsBriefingSettingRepository.class);
        NewsBriefingRepository briefingRepository = mock(NewsBriefingRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        NotificationService notificationService = mock(NotificationService.class);

        AtomicReference<NewsBriefing> saved = new AtomicReference<>();
        when(briefingRepository.existsByUserIdAndBriefingDate(any(), any())).thenReturn(false);
        when(briefingRepository.save(any())).thenAnswer(invocation -> {
            NewsBriefing briefing = invocation.getArgument(0);
            saved.set(briefing);
            return briefing;
        });

        NaverNewsApiClient naverNewsApiClient = new NaverNewsApiClient(RestClient.builder());
        ReflectionTestUtils.setField(naverNewsApiClient, "clientId", System.getenv("NAVER_CLIENT_ID"));
        ReflectionTestUtils.setField(naverNewsApiClient, "clientSecret", System.getenv("NAVER_CLIENT_SECRET"));
        ReflectionTestUtils.setField(naverNewsApiClient, "apiUrl", "https://naverapihub.apigw.ntruss.com/search/v1/news");

        GeminiApiClient geminiApiClient = new GeminiApiClient(RestClient.builder());
        ReflectionTestUtils.setField(geminiApiClient, "apiKey", System.getenv("GEMINI_API_KEY"));
        ReflectionTestUtils.setField(geminiApiClient, "judgeApiUrl",
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-lite:generateContent");
        ReflectionTestUtils.setField(geminiApiClient, "answerApiUrl",
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-lite:generateContent");

        AiNewsService aiNewsService = new AiNewsService(
                settingRepository, briefingRepository, userRepository,
                naverNewsApiClient, geminiApiClient, notificationService, new ObjectMapper());

        User user = User.builder().loginId("tester").name("테스터").role(Role.USER).isActive(true).build();
        NewsBriefingSetting setting = NewsBriefingSetting.builder().user(user).outletDomain(OUTLET_DOMAIN).build();

        boolean created = aiNewsService.generateBriefingForUser(setting, LocalDate.now());

        System.out.println("=== 생성 여부 === " + created);
        if (saved.get() != null) {
            System.out.println("=== 브리핑 본문 ===");
            System.out.println(saved.get().getContent());
            System.out.println("=== 근거 기사(JSON) ===");
            System.out.println(saved.get().getSourceLinksJson());
        }
    }
}
