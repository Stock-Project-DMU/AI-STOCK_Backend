package com.teamfp.aistock.domain.ai.service;

import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.teamfp.aistock.domain.ai.dto.response.NewsBriefingSettingResponse;
import com.teamfp.aistock.domain.ai.entity.NewsBriefing;
import com.teamfp.aistock.domain.ai.entity.NewsBriefingSetting;
import com.teamfp.aistock.domain.ai.repository.NewsBriefingRepository;
import com.teamfp.aistock.domain.ai.repository.NewsBriefingSettingRepository;
import com.teamfp.aistock.domain.notification.service.NotificationService;
import com.teamfp.aistock.domain.user.entity.Role;
import com.teamfp.aistock.domain.user.entity.User;
import com.teamfp.aistock.domain.user.repository.UserRepository;
import com.teamfp.aistock.infra.gemini.GeminiApiClient;
import com.teamfp.aistock.infra.gemini.dto.GeminiResponse;
import com.teamfp.aistock.infra.naver.NaverNewsApiClient;
import com.teamfp.aistock.infra.naver.dto.NaverNewsSearchResponse;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiNewsServiceTest {

    private static final Long USER_ID = 1L;

    @Mock private NewsBriefingSettingRepository newsBriefingSettingRepository;
    @Mock private NewsBriefingRepository newsBriefingRepository;
    @Mock private UserRepository userRepository;
    @Mock private NaverNewsApiClient naverNewsApiClient;
    @Mock private GeminiApiClient geminiApiClient;
    @Mock private NotificationService notificationService;

    private AiNewsService aiNewsService;

    private final User user = User.builder().loginId("tester").name("테스터").role(Role.USER).isActive(true).build();

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = JsonMapper.builder().build();
        aiNewsService = new AiNewsService(newsBriefingSettingRepository, newsBriefingRepository, userRepository,
                naverNewsApiClient, geminiApiClient, notificationService, objectMapper);
    }

    @Nested
    @DisplayName("언론사·브리핑 시각 설정 저장")
    class UpdateMySetting {

        @Test
        @DisplayName("처음 설정하면 지정한 브리핑 시각으로 새 설정을 만든다")
        void success_createsNewSettingWithGivenHour() {
            when(newsBriefingSettingRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());
            when(userRepository.getReferenceById(USER_ID)).thenReturn(user);
            when(newsBriefingSettingRepository.save(any(NewsBriefingSetting.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            NewsBriefingSettingResponse response = aiNewsService.updateMySetting(USER_ID, "hankyung.com", LocalTime.of(22, 15, 30));

            assertThat(response.outletDomain()).isEqualTo("hankyung.com");
            assertThat(response.briefingTime()).isEqualTo(LocalTime.of(22, 15, 30));
        }

        @Test
        @DisplayName("이미 설정이 있으면 언론사와 시각을 함께 바꾼다")
        void success_updatesExistingSettingHour() {
            NewsBriefingSetting existing = NewsBriefingSetting.builder()
                    .user(user).outletDomain("mk.co.kr").briefingTime(LocalTime.of(7, 0, 0)).build();
            when(newsBriefingSettingRepository.findByUserId(USER_ID)).thenReturn(Optional.of(existing));
            when(newsBriefingSettingRepository.save(any(NewsBriefingSetting.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            NewsBriefingSettingResponse response = aiNewsService.updateMySetting(USER_ID, "hankyung.com", LocalTime.of(9, 0, 0));

            assertThat(response.outletDomain()).isEqualTo("hankyung.com");
            assertThat(response.briefingTime()).isEqualTo(LocalTime.of(9, 0, 0));
            verify(userRepository, never()).getReferenceById(anyLong());
        }

        @Test
        @DisplayName("브리핑 시각을 지정하지 않고(null) 엔티티를 직접 만들면 기본값 07:00:00이 된다")
        void defaultsToSevenWhenHourNotProvided() {
            NewsBriefingSetting setting = NewsBriefingSetting.builder().user(user).outletDomain("mk.co.kr").build();
            assertThat(setting.getBriefingTime()).isEqualTo(NewsBriefingSetting.DEFAULT_BRIEFING_TIME);
        }
    }

    @Nested
    @DisplayName("정기 브리핑 배치 — 시각 필터링")
    class GenerateDailyBriefings {

        // 시:분:초 매칭은 findDueSettings() 안에서 DB 조건(briefingTime 등호 비교)으로
        // 처리하므로(PR#45 리뷰 반려 사유 4번 대응, 2026-09-28), 여기서는 레포지토리가
        // 반환한 대상자를 서비스가 그대로 처리하는지만 검증한다 — 이전엔 테스트 쪽과 서비스
        // 쪽이 각각 LocalDateTime.now()를 따로 재는 구조라 그 사이 초 경계를 넘으면 가끔
        // 실패하는 플레이키 테스트였는데, 이 구조에서는 그 레이스 자체가 사라진다.
        @Test
        @DisplayName("findDueSettings()가 돌려준 대상자만 처리한다")
        void processesOnlySettingsReturnedByRepository() {
            NewsBriefingSetting due = NewsBriefingSetting.builder()
                    .user(user).outletDomain("hankyung.com").briefingTime(LocalTime.of(9, 0, 0)).build();
            ReflectionTestUtils.setField(due, "settingId", 7L);
            when(newsBriefingSettingRepository.findDueSettings(any(), any()))
                    .thenReturn(List.of(due));
            when(newsBriefingSettingRepository.claimBriefingAttempt(eq(7L), any(), any())).thenReturn(1);

            // generateDailyBriefings()는 self 프록시(@Lazy)를 통해 generateBriefingForUser()를
            // 호출하므로, 실제 프록시 대신 스파이를 주입해 어떤 setting으로 호출됐는지만 검증한다
            // (StockBroadcastServiceTest와 달리 같은 클래스의 self-invocation 우회 검증이 목적).
            AiNewsService self = spy(aiNewsService);
            doReturn(true).when(self).generateBriefingForUser(any(), any());
            ReflectionTestUtils.setField(aiNewsService, "self", self);

            aiNewsService.generateDailyBriefings();

            verify(self, times(1)).generateBriefingForUser(eq(due), any());
        }

        @Test
        @DisplayName("findDueSettings()가 빈 목록을 돌려주면 아무도 처리하지 않는다")
        void doesNothingWhenNoOneIsDue() {
            when(newsBriefingSettingRepository.findDueSettings(any(), any()))
                    .thenReturn(List.of());

            AiNewsService self = spy(aiNewsService);
            ReflectionTestUtils.setField(aiNewsService, "self", self);

            aiNewsService.generateDailyBriefings();

            verify(self, never()).generateBriefingForUser(any(), any());
        }
    }

    @Nested
    @DisplayName("사용자 1명분 브리핑 생성 — 실패해도 안내 문구를 화면에 남긴다")
    class GenerateBriefingForUser {

        private final NewsBriefingSetting setting = NewsBriefingSetting.builder()
                .user(user).outletDomain("hankyung.com").briefingTime(LocalTime.of(7, 0, 0)).build();

        @Test
        @DisplayName("검색된 기사가 없으면, 그냥 건너뛰지 않고 안내 문구가 담긴 행을 저장한다(알림은 안 보냄)")
        void savesGuidanceMessageWhenNoArticlesFound() {
            when(newsBriefingRepository.existsByUserIdAndBriefingDate(any(), any())).thenReturn(false);
            when(naverNewsApiClient.searchByOutlet("hankyung.com")).thenReturn(new NaverNewsSearchResponse(List.of()));

            boolean generated = aiNewsService.generateBriefingForUser(setting, java.time.LocalDate.now());

            assertThat(generated).isFalse();
            var captor = org.mockito.ArgumentCaptor.forClass(NewsBriefing.class);
            verify(newsBriefingRepository).save(captor.capture());
            assertThat(captor.getValue().getContent()).contains("시황 관련 기사를 찾지 못했어요");
            assertThat(captor.getValue().getSourceLinksJson()).isEqualTo("[]");
            verify(notificationService, never()).notify(anyLong(), any(), anyString(), anyString());
        }

        @Test
        @DisplayName("기사는 있지만 근거 검증을 끝내 통과 못 하면, '기사 없음'과는 다른 문구를 저장하고 링크는 붙이지 않는다(AI가 못 미더워한 기사를 굳이 보여줄 필요 없음, 알림도 안 보냄)")
        void savesGuidanceMessageWhenGroundingNeverPasses() {
            when(newsBriefingRepository.existsByUserIdAndBriefingDate(any(), any())).thenReturn(false);
            var article = new NaverNewsSearchResponse.NaverNewsResult("제목", "설명", "https://example.com/1", "2026-09-21", "한국경제");
            when(naverNewsApiClient.searchByOutlet("hankyung.com")).thenReturn(new NaverNewsSearchResponse(List.of(article)));
            // 요약 호출도, 검증 호출도 전부 같은 mock을 거치는데 응답이 "SAFE"로 시작하지 않으면
            // isGrounded()가 항상 실패로 보므로, MAX_SUMMARY_ATTEMPTS(5)를 다 채우고 폐기된다.
            when(geminiApiClient.generate(any())).thenReturn(new GeminiResponse("UNSAFE: 지어낸 내용", null, null));

            boolean generated = aiNewsService.generateBriefingForUser(setting, java.time.LocalDate.now());

            assertThat(generated).isFalse();
            var captor = org.mockito.ArgumentCaptor.forClass(NewsBriefing.class);
            verify(newsBriefingRepository).save(captor.capture());
            assertThat(captor.getValue().getContent()).contains("신뢰할 수 있는 요약을 만들지 못했어요");
            assertThat(captor.getValue().getSourceLinksJson()).isEqualTo("[]");
            verify(notificationService, never()).notify(anyLong(), any(), anyString(), anyString());
        }
    }
}
