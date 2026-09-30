package com.teamfp.aistock.domain.user.service;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.teamfp.aistock.domain.user.dto.request.PasswordChangeRequest;
import com.teamfp.aistock.domain.user.dto.request.PasswordVerifyRequest;
import com.teamfp.aistock.domain.user.dto.request.SurveyRequest;
import com.teamfp.aistock.domain.user.dto.request.UpdateUserRequest;
import com.teamfp.aistock.domain.user.dto.response.InvestmentProfileResponse;
import com.teamfp.aistock.domain.user.dto.response.UserInfoResponse;
import com.teamfp.aistock.domain.user.entity.InvestmentProfile;
import com.teamfp.aistock.domain.user.entity.Role;
import com.teamfp.aistock.domain.user.entity.User;
import com.teamfp.aistock.domain.user.repository.InvestmentProfileRepository;
import com.teamfp.aistock.domain.user.repository.SocialAccountRepository;
import com.teamfp.aistock.domain.user.entity.SocialAccount;
import com.teamfp.aistock.domain.user.entity.SocialProvider;
import com.teamfp.aistock.domain.user.repository.UserRepository;
import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;
import com.teamfp.aistock.global.redis.RedisTokenService;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * feature/mypage-profit — UserService(내 정보 조회/수정, 투자성향 설문) 단위 테스트.
 * ObjectMapper는 실제 Jackson 인스턴스를 그대로 쓴다(JSON 직렬화 결과 자체를 검증하므로 모킹할 이유가 없음).
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private InvestmentProfileRepository investmentProfileRepository;

    @Mock
    private SocialAccountRepository socialAccountRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private RedisTokenService redisTokenService;

    private UserService userService;

    private static final Long USER_ID = 1L;

    private User user;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = JsonMapper.builder().build();
        userService = new UserService(userRepository, investmentProfileRepository, socialAccountRepository, objectMapper, passwordEncoder, redisTokenService);

        user = User.builder()
                .userId(USER_ID)
                .loginId("tester")
                .password("encoded-old-password")
                .name("테스터")
                .email("old@example.com")
                .role(Role.USER)
                .isActive(true)
                .build();
    }

    @Test
    @DisplayName("내 정보에 연결된 소셜 로그인 제공자를 포함한다")
    void getMyInfoIncludesSocialProvider() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(socialAccountRepository.findFirstByUser_UserIdOrderBySocialIdAsc(USER_ID))
                .thenReturn(Optional.of(SocialAccount.builder().user(user).provider(SocialProvider.NAVER).providerId("naver-id").build()));

        assertThat(userService.getMyInfo(USER_ID).socialProvider()).isEqualTo(SocialProvider.NAVER);
    }

    @Test
    @DisplayName("기본 정보만 저장하면 설문 결과를 조회하거나 변경하지 않는다")
    void updateProfileWithoutInvestmentPreservesSurvey() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("current-password", "encoded-old-password")).thenReturn(true);
        var request = new com.teamfp.aistock.domain.user.dto.request.ProfileUpdateRequest(
                "current-password", new UpdateUserRequest("새이름", "old@example.com"), null, null);

        UserInfoResponse response = userService.updateProfile(USER_ID, request);

        assertThat(response.name()).isEqualTo("새이름");
        org.mockito.Mockito.verifyNoInteractions(investmentProfileRepository);
    }

    @Nested
    @DisplayName("투자 성향 설문 완료 확인")
    class SurveyCompletion {
        @Test
        @DisplayName("가입 시 생성된 기본 투자 정보는 설문 완료로 취급하지 않는다")
        void defaultProfileRequiresSurvey() {
            InvestmentProfile profile = InvestmentProfile.builder().user(user)
                    .investmentTendency(3).fundTendency(1).build();
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(investmentProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(profile));

            assertThat(userService.getInvestmentProfile(USER_ID).surveyCompleted()).isFalse();
            assertThatThrownBy(() -> userService.requireCompletedSurvey(USER_ID))
                    .isInstanceOf(CustomException.class)
                    .extracting(error -> ((CustomException) error).getErrorCode())
                    .isEqualTo(ErrorCode.SURVEY_REQUIRED);
        }

        @Test
        @DisplayName("설문 응답이 저장된 계정은 상담을 이용할 수 있다")
        void savedSurveyAllowsChat() {
            InvestmentProfile profile = InvestmentProfile.builder().user(user)
                    .investmentTendency(3).fundTendency(1).surveyAnswers("[1,2,3,1,4,5,3,3]").build();
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(investmentProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(profile));

            assertThat(userService.getInvestmentProfile(USER_ID).surveyCompleted()).isTrue();
            userService.requireCompletedSurvey(USER_ID);
        }

        @Test
        @DisplayName("투자 정보가 없는 계정도 설문 완료 전에는 상담을 이용할 수 없다")
        void missingProfileRequiresSurvey() {
            when(investmentProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> userService.requireCompletedSurvey(USER_ID))
                    .isInstanceOf(CustomException.class)
                    .extracting(error -> ((CustomException) error).getErrorCode())
                    .isEqualTo(ErrorCode.SURVEY_REQUIRED);
        }
    }

    @Nested
    @DisplayName("내 정보 조회")
    class GetMyInfo {

        @Test
        @DisplayName("존재하는 유저면 UserInfoResponse로 변환해 반환한다")
        void success() {
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

            UserInfoResponse response = userService.getMyInfo(USER_ID);

            assertThat(response.loginId()).isEqualTo("tester");
            assertThat(response.email()).isEqualTo("old@example.com");
        }

        @Test
        @DisplayName("존재하지 않는 유저면 USER_NOT_FOUND 예외를 던진다")
        void fail_userNotFound() {
            when(userRepository.findById(anyLong())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> userService.getMyInfo(999L))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.USER_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("내 정보 수정")
    class UpdateMyInfo {

        @Test
        @DisplayName("새 이메일이 중복되지 않으면 name/email이 반영된다")
        void success() {
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(userRepository.existsByEmail("new@example.com")).thenReturn(false);

            UserInfoResponse response = userService.updateMyInfo(USER_ID, new UpdateUserRequest("새이름", "new@example.com"));

            assertThat(response.name()).isEqualTo("새이름");
            assertThat(response.email()).isEqualTo("new@example.com");
        }

        @Test
        @DisplayName("본인이 기존과 같은 이메일을 그대로 보내면 중복 검사를 하지 않는다")
        void success_sameEmailSkipsDuplicateCheck() {
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

            userService.updateMyInfo(USER_ID, new UpdateUserRequest("새이름", "old@example.com"));

            verify(userRepository, never()).existsByEmail(anyString());
        }

        @Test
        @DisplayName("다른 유저가 이미 쓰고 있는 이메일이면 DUPLICATE_EMAIL 예외를 던진다")
        void fail_duplicateEmail() {
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(userRepository.existsByEmail("taken@example.com")).thenReturn(true);

            assertThatThrownBy(() -> userService.updateMyInfo(USER_ID, new UpdateUserRequest("새이름", "taken@example.com")))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.DUPLICATE_EMAIL);

            assertThat(user.getEmail()).isEqualTo("old@example.com"); // 변경되지 않음
        }

        @Test
        @DisplayName("본인 이메일을 대소문자만 다르게 재입력해도 중복 검사를 하지 않는다 (DB 콜레이션이 대소문자 구분 안 함)")
        void success_sameEmailDifferentCaseSkipsDuplicateCheck() {
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user)); // user.email = "old@example.com"

            userService.updateMyInfo(USER_ID, new UpdateUserRequest("새이름", "OLD@EXAMPLE.COM"));

            verify(userRepository, never()).existsByEmail(anyString());
        }
    }

    @Nested
    @DisplayName("투자성향 설문 저장")
    class SaveSurvey {

        @Test
        @DisplayName("4번 문항의 중복 선택을 모두 저장하고 대표 답은 가장 높은 위험 경험으로 계산한다")
        void savesMultipleExperienceAnswers() {
            when(investmentProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

            userService.saveSurvey(USER_ID, new SurveyRequest(List.of(1, 2, 3, 1, 4, 5, 3, 3), List.of(1, 3, 5)));

            org.mockito.ArgumentCaptor<InvestmentProfile> captor = org.mockito.ArgumentCaptor.forClass(InvestmentProfile.class);
            verify(investmentProfileRepository).save(captor.capture());
            assertThat(captor.getValue().getSurveyAnswers()).contains("\"experienceAnswers\":[1,3,5]");
        }

        @Test
        @DisplayName("4번 문항 대표 답과 중복 선택 목록이 다르면 거부한다")
        void rejectsInconsistentExperienceAnswers() {
            assertThatThrownBy(() -> userService.saveSurvey(USER_ID,
                    new SurveyRequest(List.of(1, 2, 3, 3, 4, 5, 3, 3), List.of(1, 3))))
                    .isInstanceOf(CustomException.class)
                    .extracting(error -> ((CustomException) error).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_INPUT);
        }

        // 투자성향·자금성향·투자레벨 전부 answers만으로 서버가 계산한다(SurveyTendencyEvaluator,
        // SurveyLevelEvaluator). [1,2,3,1,4,5,3,3] → 투자성향 5(공격투자형 — 4번 문항이
        // "6-답"으로 방향이 뒤집혀서 1(최고위험)이 최대 기여를 함, 우혁 2026-09-29 재반려
        // 사유 3-2 대응), 자금성향 1(수익추구형), 레벨 EXPERT(5번=4, 8번=3).
        private SurveyRequest requestOf(List<Integer> answers) {
            return new SurveyRequest(answers);
        }

        @Test
        @DisplayName("처음 제출하면 새 InvestmentProfile을 생성해 저장한다")
        void success_createsNewProfile() {
            when(investmentProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

            InvestmentProfileResponse response = userService.saveSurvey(USER_ID, requestOf(List.of(1, 2, 3, 1, 4, 5, 3, 3)));

            assertThat(response.investmentTendency()).isEqualTo(5);
            assertThat(response.investmentLevel()).isEqualTo(com.teamfp.aistock.domain.user.entity.InvestmentLevel.EXPERT);
            assertThat(response.fundTendency()).isEqualTo(1);
            verify(investmentProfileRepository).save(any(InvestmentProfile.class));
        }

        @Test
        @DisplayName("이미 설문을 제출한 적이 있으면 기존 행을 갱신한다(신규 저장 없음)")
        void success_updatesExistingProfile() {
            InvestmentProfile existing = InvestmentProfile.builder()
                    .user(user)
                    .investmentTendency(1)
                    .fundTendency(1)
                    .surveyAnswers("[0]")
                    .build();
            when(investmentProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(existing));

            // [3,1,1,1,4,1,1,3] → 투자성향 2(안정추구형 — 4번 문항 답 1이 "6-답" 반전으로 최대
            // 기여를 해서 예전 기준(안정형)보다 한 구간 높게 나옴), 자금성향 3(목표달성형),
            // 레벨 EXPERT(5번=4, 8번=3)
            InvestmentProfileResponse response = userService.saveSurvey(USER_ID, requestOf(List.of(3, 1, 1, 1, 4, 1, 1, 3)));

            assertThat(response.investmentTendency()).isEqualTo(2);
            assertThat(response.investmentLevel()).isEqualTo(com.teamfp.aistock.domain.user.entity.InvestmentLevel.EXPERT);
            assertThat(response.fundTendency()).isEqualTo(3);
            verify(investmentProfileRepository, never()).save(any(InvestmentProfile.class));
            verify(userRepository, never()).findById(anyLong()); // 갱신 시엔 User를 다시 조회할 필요가 없다
        }

        @Test
        @DisplayName("동시에 두 번 최초 제출되어 uq_user_profile 유니크 제약을 위반하면 OPTIMISTIC_LOCK_CONFLICT로 변환한다")
        void fail_concurrentFirstSubmitRace() {
            when(investmentProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(investmentProfileRepository.save(any(InvestmentProfile.class)))
                    .thenThrow(new org.springframework.dao.DataIntegrityViolationException(
                            "Duplicate entry '1' for key 'investment_profile.uq_user_profile'"));

            assertThatThrownBy(() -> userService.saveSurvey(USER_ID, requestOf(List.of(1, 2, 3, 1, 4, 5, 3, 3))))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.OPTIMISTIC_LOCK_CONFLICT);
        }

        @Test
        @DisplayName("uq_user_profile과 무관한 다른 저장 실패는 그대로 흘려보낸다")
        void fail_unrelatedDataIntegrityViolationPropagates() {
            when(investmentProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            org.springframework.dao.DataIntegrityViolationException unrelated =
                    new org.springframework.dao.DataIntegrityViolationException("some other constraint violated");
            when(investmentProfileRepository.save(any(InvestmentProfile.class))).thenThrow(unrelated);

            assertThatThrownBy(() -> userService.saveSurvey(USER_ID, requestOf(List.of(1, 2, 3, 1, 4, 5, 3, 3))))
                    .isSameAs(unrelated);
        }
    }

    @Nested
    @DisplayName("비밀번호 확인")
    class VerifyPassword {

        @Test
        @DisplayName("현재 비밀번호가 일치하면 예외 없이 통과한다")
        void success() {
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("current-pw", "encoded-old-password")).thenReturn(true);

            userService.verifyPassword(USER_ID, new PasswordVerifyRequest("current-pw"));
        }

        @Test
        @DisplayName("비밀번호가 일치하지 않으면 INVALID_PASSWORD 예외를 던진다")
        void fail_wrongPassword() {
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("wrong-pw", "encoded-old-password")).thenReturn(false);

            assertThatThrownBy(() -> userService.verifyPassword(USER_ID, new PasswordVerifyRequest("wrong-pw")))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_PASSWORD);
        }

        @Test
        @DisplayName("소셜 로그인 전용 계정(password=null)이면 PASSWORD_NOT_SET 예외를 던진다")
        void fail_socialOnlyAccount() {
            User socialUser = User.builder()
                    .name("소셜유저")
                    .role(Role.USER)
                    .isActive(true)
                    .build();
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(socialUser));

            assertThatThrownBy(() -> userService.verifyPassword(USER_ID, new PasswordVerifyRequest("아무거나")))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.PASSWORD_NOT_SET);

            verify(passwordEncoder, never()).matches(anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("비밀번호 변경")
    class ChangePassword {

        @Test
        @DisplayName("현재 비밀번호가 일치하면 새 비밀번호로 바꾸고 Refresh Token을 폐기한다")
        void success() {
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("current-pw", "encoded-old-password")).thenReturn(true);
            when(passwordEncoder.encode("new-pw12")).thenReturn("encoded-new-password");

            userService.changePassword(USER_ID, new PasswordChangeRequest("current-pw", "new-pw12"));

            assertThat(user.getPassword()).isEqualTo("encoded-new-password");
            verify(redisTokenService).deleteRefreshToken(USER_ID);
        }

        @Test
        @DisplayName("현재 비밀번호가 틀리면 INVALID_PASSWORD 예외를 던지고 비밀번호를 바꾸지 않는다")
        void fail_wrongCurrentPassword() {
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("wrong-pw", "encoded-old-password")).thenReturn(false);

            assertThatThrownBy(() -> userService.changePassword(USER_ID, new PasswordChangeRequest("wrong-pw", "new-pw12")))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_PASSWORD);

            assertThat(user.getPassword()).isEqualTo("encoded-old-password");
            verify(redisTokenService, never()).deleteRefreshToken(anyLong());
        }
    }
}
