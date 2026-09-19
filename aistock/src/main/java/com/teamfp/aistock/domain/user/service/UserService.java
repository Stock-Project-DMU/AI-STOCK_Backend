package com.teamfp.aistock.domain.user.service;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.teamfp.aistock.domain.user.dto.request.PasswordChangeRequest;
import com.teamfp.aistock.domain.user.dto.request.PasswordVerifyRequest;
import com.teamfp.aistock.domain.user.dto.request.SurveyRequest;
import com.teamfp.aistock.domain.user.dto.request.UpdateUserRequest;
import com.teamfp.aistock.domain.user.dto.response.InvestmentProfileResponse;
import com.teamfp.aistock.domain.user.dto.response.UserInfoResponse;
import com.teamfp.aistock.domain.user.entity.InvestmentProfile;
import com.teamfp.aistock.domain.user.entity.User;
import com.teamfp.aistock.domain.user.repository.InvestmentProfileRepository;
import com.teamfp.aistock.domain.user.repository.UserRepository;
import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;
import com.teamfp.aistock.global.redis.RedisTokenService;

import lombok.RequiredArgsConstructor;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final InvestmentProfileRepository investmentProfileRepository;
    private final ObjectMapper objectMapper;
    private final PasswordEncoder passwordEncoder;
    private final RedisTokenService redisTokenService;

    @Transactional
    public UserInfoResponse updateProfile(Long userId, com.teamfp.aistock.domain.user.dto.request.ProfileUpdateRequest request) {
        matchOrThrow(request.currentPassword(), findUser(userId));
        UserInfoResponse result = updateMyInfo(userId, request.user());
        if (request.investment() != null) updateInvestmentProfile(userId, request.investment());
        if (request.passwordChange() != null) changePassword(userId, request.passwordChange());
        return result;
    }

    @Transactional(readOnly = true)
    public UserInfoResponse getMyInfo(Long userId) {
        return UserInfoResponse.from(findUser(userId));
    }

    /**
     * 내 정보 수정. loginId/비밀번호/투자성향은 이 API로 바꾸지 않는다.
     * 이메일은 users.email이 UNIQUE라 다른 유저가 이미 쓰고 있으면 DUPLICATE_EMAIL로 막는다
     * (본인이 기존과 같은 이메일을 그대로 다시 보내는 경우는 중복 검사에서 제외).
     *
     * DB(schema.sql 기본 콜레이션 utf8mb4_unicode_ci)는 대소문자를 구분하지 않고 이메일 중복을
     * 판단하므로, "같은 이메일인지" 비교도 equalsIgnoreCase로 맞춘다 — equals로 비교하면
     * 본인이 대소문자만 다르게 재입력했을 때 "다른 이메일"로 오인해 existsByEmail이 자기 자신의
     * 행과 매칭되어 DUPLICATE_EMAIL을 잘못 던지게 된다.
     *
     * request.email()은 컨트롤러의 @Valid(@NotBlank)가 이미 null을 막아주지만, 이 서비스
     * 메서드를 @Valid 없이 직접 호출하는 경로(관리자 배치 작업 등)가 나중에 생겨도
     * equalsIgnoreCase에서 NPE 대신 명확한 INVALID_INPUT으로 응답하도록 여기서도 한 번 막는다.
     */
    @Transactional
    public UserInfoResponse updateMyInfo(Long userId, UpdateUserRequest request) {
        if (request.email() == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT);
        }
        User user = findUser(userId);
        if (!request.email().equalsIgnoreCase(user.getEmail()) && userRepository.existsByEmail(request.email())) {
            throw new CustomException(ErrorCode.DUPLICATE_EMAIL);
        }
        user.updateInfo(request.name(), request.email());
        if (request.birthdate() != null) user.updateBirthdate(request.birthdate());
        return UserInfoResponse.from(user);
    }

    // investment_profile.uq_user_profile — 같은 유저의 설문 최초 제출이 동시에 두 건 이상
    // 들어올 때 걸리는 유니크 제약. DataIntegrityViolationException의 원인이 이 제약인지
    // 메시지로 구분한다(HoldingSettlementService.UNIQUE_CONSTRAINT_NAME과 동일한 패턴).
    private static final String UNIQUE_CONSTRAINT_NAME = "uq_user_profile";

    @Transactional(readOnly = true)
    public InvestmentProfileResponse getInvestmentProfile(Long userId) {
        findUser(userId);
        return investmentProfileRepository.findByUserId(userId).map(InvestmentProfileResponse::from).orElse(null);
    }

    @Transactional
    public InvestmentProfileResponse updateInvestmentProfile(Long userId,
            com.teamfp.aistock.domain.user.dto.request.InvestmentProfileUpdateRequest request) {
        User user = findUser(userId);
        InvestmentProfile profile = investmentProfileRepository.findByUserId(userId)
                .orElseGet(() -> investmentProfileRepository.save(InvestmentProfile.builder().user(user)
                        .investmentTendency(request.investmentTendency()).fundTendency(request.fundTendency())
                        .investmentLevel(request.investmentLevel()).build()));
        profile.updatePreferences(request.investmentTendency(), request.fundTendency(), request.investmentLevel());
        return InvestmentProfileResponse.from(profile);
    }

    /**
     * 투자성향 설문 저장. investment_profile은 1인 1행(uq_user_profile)이라 이미 있으면
     * updateSurvey()로 덮어쓰고, 처음 제출하는 경우에만 새로 만든다.
     *
     * find-or-create 사이에 같은 유저의 최초 제출 요청이 동시에 두 건 들어오면 둘 다 profile을
     * null로 보고 각자 새 행을 저장하려다 두 번째 저장이 uq_user_profile 유니크 제약을 위반할 수
     * 있다. 이는 재시도하면 해소되는 순수 타이밍 경합이라, HoldingSettlementService.
     * increaseOrCreate()와 동일하게 OPTIMISTIC_LOCK_CONFLICT(409, "다시 시도해 달라")로 변환한다.
     */
    @Transactional
    public InvestmentProfileResponse saveSurvey(Long userId, SurveyRequest request) {
        var investmentLevel = SurveyLevelEvaluator.evaluate(request.answers());
        // RedisStockCacheService의 다른 ObjectMapper 사용처와 동일하게, 직렬화 실패를 raw
        // 예외로 흘려보내지 않고 CustomException으로 감싼다(List<Integer> 특성상 실질적으로는
        // 거의 발생하지 않지만, 프로젝트 전체의 예외 처리 관례와 일관성을 맞춘다).
        String surveyAnswersJson;
        try {
            surveyAnswersJson = objectMapper.writeValueAsString(request.answers());
        } catch (JacksonException e) {
            throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR, e);
        }

        InvestmentProfile profile = investmentProfileRepository.findByUserId(userId).orElse(null);
        if (profile == null) {
            User user = findUser(userId);
            profile = InvestmentProfile.builder()
                    .user(user)
                    .investmentTendency(request.investmentTendency())
                    .fundTendency(request.fundTendency())
                    .surveyAnswers(surveyAnswersJson)
                    .investmentLevel(investmentLevel)
                    .build();
            try {
                investmentProfileRepository.save(profile);
            } catch (DataIntegrityViolationException e) {
                if (isUniqueConstraintViolation(e)) {
                    throw new CustomException(ErrorCode.OPTIMISTIC_LOCK_CONFLICT, e);
                }
                throw e;
            }
        } else {
            profile.updateSurvey(request.investmentTendency(), request.fundTendency(), surveyAnswersJson);
            profile.updatePreferences(request.investmentTendency(), request.fundTendency(), investmentLevel);
        }

        return InvestmentProfileResponse.from(profile);
    }

    // 비밀번호 확인(ADMIN_API_BACKEND_HANDOFF.md 5.3). 실패해도 예외만 던지고 별도 반환값은
    // 없다 — 컨트롤러가 "예외 없이 통과 = 확인 성공"으로 응답한다(AuthService.login()의
    // 비밀번호 검증과 동일한 방식).
    @Transactional(readOnly = true)
    public void verifyPassword(Long userId, PasswordVerifyRequest request) {
        matchOrThrow(request.password(), findUser(userId));
    }

    /**
     * 비밀번호 변경. 현재 비밀번호를 먼저 확인한 뒤 새 비밀번호로 교체하고, 변경 성공 시 기존
     * Refresh Token을 폐기한다(handoff 문서 5.3 요구사항 — 비밀번호가 바뀌면 그 전에 발급된
     * 세션은 더 이상 유효하지 않아야 하므로, AuthService.logout()과 동일하게
     * RedisTokenService.deleteRefreshToken()을 호출한다). Access Token까지 즉시 무효화하려면
     * 블랙리스트 등록이 필요하지만, 이 메서드는 Access Token 문자열 자체를 받지 않으므로(요청
     * 헤더가 필요) 그 부분은 다루지 않는다 — 다음 재발급(refresh) 시점에 자연히 막힌다.
     */
    @Transactional
    public void changePassword(Long userId, PasswordChangeRequest request) {
        User user = findUser(userId);
        matchOrThrow(request.currentPassword(), user);
        user.changePassword(passwordEncoder.encode(request.newPassword()));
        redisTokenService.deleteRefreshToken(userId);
    }

    // 소셜 로그인 전용 계정(password == null)이 확인/변경을 시도하면 PasswordEncoder.matches()가
    // NPE 대신 IllegalArgumentException("encoded password cannot be null")을 던지므로, 그 전에
    // 명확한 PASSWORD_NOT_SET으로 막는다.
    private void matchOrThrow(String rawPassword, User user) {
        if (user.getPassword() == null) {
            throw new CustomException(ErrorCode.PASSWORD_NOT_SET);
        }
        if (!passwordEncoder.matches(rawPassword, user.getPassword())) {
            throw new CustomException(ErrorCode.INVALID_PASSWORD);
        }
    }

    private boolean isUniqueConstraintViolation(DataIntegrityViolationException e) {
        Throwable cause = e.getMostSpecificCause();
        return cause.getMessage() != null && cause.getMessage().contains(UNIQUE_CONSTRAINT_NAME);
    }

    private User findUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
    }
}
