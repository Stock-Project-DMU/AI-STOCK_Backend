package com.teamfp.aistock.global.redis;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RedisServiceTests {

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private RedisTokenService redisTokenService;
    private RedisAuthCodeService redisAuthCodeService;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        redisTokenService = new RedisTokenService(redisTemplate);
        redisAuthCodeService = new RedisAuthCodeService(redisTemplate);
    }

    @Test
    @DisplayName("Refresh Token 저장 성공")
    void saveRefreshToken_Success() {
        // given
        Long userId = 1L;
        String refreshToken = "refreshToken123";

        // when
        redisTokenService.saveRefreshToken(userId, refreshToken);

        // then
        verify(valueOperations).set("auth:refresh:" + userId, refreshToken, Duration.ofDays(14));
    }

    @Test
    @DisplayName("Refresh Token 조회 성공")
    void getRefreshToken_Success() {
        // given
        Long userId = 1L;
        String expectedToken = "refreshToken123";
        when(valueOperations.get("auth:refresh:" + userId)).thenReturn(expectedToken);

        // when
        String actualToken = redisTokenService.getRefreshToken(userId);

        // then
        assertThat(actualToken).isEqualTo(expectedToken);
    }

    @Test
    @DisplayName("Refresh Token 검증 - 일치하는 경우")
    void isRefreshTokenValid_True() {
        // given
        Long userId = 1L;
        String token = "validToken";
        when(valueOperations.get("auth:refresh:" + userId)).thenReturn(token);

        // when
        boolean isValid = redisTokenService.isRefreshTokenValid(userId, token);

        // then
        assertThat(isValid).isTrue();
    }

    @Test
    @DisplayName("Refresh Token 검증 - 불일치하는 경우")
    void isRefreshTokenValid_False() {
        // given
        Long userId = 1L;
        String token = "invalidToken";
        when(valueOperations.get("auth:refresh:" + userId)).thenReturn("anotherToken");

        // when
        boolean isValid = redisTokenService.isRefreshTokenValid(userId, token);

        // then
        assertThat(isValid).isFalse();
    }

    @Test
    @DisplayName("Refresh Token 삭제 성공")
    void deleteRefreshToken_Success() {
        // given
        Long userId = 1L;

        // when
        redisTokenService.deleteRefreshToken(userId);

        // then
        verify(redisTemplate).delete("auth:refresh:" + userId);
    }

    @Test
    @DisplayName("Access Token 블랙리스트 등록 성공")
    void blacklistAccessToken_Success() {
        // given
        String accessToken = "accessToken123";
        long remainingMillis = 10000;

        // when
        redisTokenService.blacklistAccessToken(accessToken, remainingMillis);

        // then
        verify(valueOperations).set("auth:blacklist:" + accessToken, "LOGOUT", Duration.ofMillis(remainingMillis));
    }

    @Test
    @DisplayName("Access Token 블랙리스트 여부 확인 - 등록된 경우")
    void isBlacklisted_True() {
        // given
        String accessToken = "blacklistedToken";
        when(redisTemplate.hasKey("auth:blacklist:" + accessToken)).thenReturn(true);

        // when
        boolean isBlacklisted = redisTokenService.isBlacklisted(accessToken);

        // then
        assertThat(isBlacklisted).isTrue();
    }

    @Test
    @DisplayName("이메일 인증 코드 저장 성공")
    void saveEmailCode_Success() {
        // given
        String email = "test@example.com";
        String code = "123456";

        // when
        redisAuthCodeService.saveEmailCode(email, code);

        // then
        verify(valueOperations).set("auth:email_code:" + email, code, Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("이메일 인증 코드 검증 - 성공 시 삭제 및 true 반환")
    void verifyAndDeleteEmailCode_Success() {
        // given
        String email = "test@example.com";
        String code = "123456";
        when(valueOperations.get("auth:email_code:" + email)).thenReturn(code);

        // when
        boolean isVerified = redisAuthCodeService.verifyAndDeleteEmailCode(email, code);

        // then
        assertThat(isVerified).isTrue();
        verify(redisTemplate).delete("auth:email_code:" + email);
    }

    @Test
    @DisplayName("이메일 인증 코드 검증 - 실패 시 삭제 미동작 및 false 반환")
    void verifyAndDeleteEmailCode_Failure() {
        // given
        String email = "test@example.com";
        String code = "123456";
        when(valueOperations.get("auth:email_code:" + email)).thenReturn("wrong_code");

        // when
        boolean isVerified = redisAuthCodeService.verifyAndDeleteEmailCode(email, code);

        // then
        assertThat(isVerified).isFalse();
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    @DisplayName("로그인 실패 횟수 증가 - 첫 실패 시 TTL 설정")
    void incrementLoginFail_FirstTime() {
        // given
        String loginId = "user1";
        when(valueOperations.increment("auth:login_fail:" + loginId)).thenReturn(1L);

        // when
        long count = redisAuthCodeService.incrementLoginFail(loginId);

        // then
        assertThat(count).isEqualTo(1L);
        verify(redisTemplate).expire("auth:login_fail:" + loginId, Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("로그인 실패 횟수 증가 - 두 번째 실패 시 TTL 재설정 안함")
    void incrementLoginFail_SecondTime() {
        // given
        String loginId = "user1";
        when(valueOperations.increment("auth:login_fail:" + loginId)).thenReturn(2L);

        // when
        long count = redisAuthCodeService.incrementLoginFail(loginId);

        // then
        assertThat(count).isEqualTo(2L);
        verify(redisTemplate, never()).expire(eq("auth:login_fail:" + loginId), any(Duration.class));
    }

    @Test
    @DisplayName("로그인 잠금 여부 확인")
    void isLoginLocked() {
        // given
        String loginId = "user1";
        when(valueOperations.get("auth:login_fail:" + loginId)).thenReturn("5");

        // when
        boolean isLocked = redisAuthCodeService.isLoginLocked(loginId);

        // then
        assertThat(isLocked).isTrue();
    }

    @Test
    @DisplayName("로그인 잠금 여부 확인 - 미달 시")
    void isLoginLocked_False() {
        // given
        String loginId = "user1";
        when(valueOperations.get("auth:login_fail:" + loginId)).thenReturn("3");

        // when
        boolean isLocked = redisAuthCodeService.isLoginLocked(loginId);

        // then
        assertThat(isLocked).isFalse();
    }

    @Test
    @DisplayName("로그인 실패 초기화")
    void resetLoginFail() {
        // given
        String loginId = "user1";

        // when
        redisAuthCodeService.resetLoginFail(loginId);

        // then
        verify(redisTemplate).delete("auth:login_fail:" + loginId);
    }
}
