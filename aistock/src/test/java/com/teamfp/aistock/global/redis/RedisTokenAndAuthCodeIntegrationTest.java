package com.teamfp.aistock.global.redis;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RedisTokenService / RedisAuthCodeService 실제 Redis 연동 테스트.
 * 기존 RedisMarketServicesTest는 RedisTemplate을 Mockito mock으로 대체하지만,
 * 이 테스트는 실제 Redis 서버(localhost:6379)에 직접 연결해 TTL·increment·삭제 같은
 * mock으로는 검증할 수 없는 동작까지 확인한다.
 * 사전 조건: Redis 서버가 localhost:6379에서 실행 중이어야 한다
 * (docker-compose up -d 로 띄운 aistock-redis, 혹은 CI의 redis:7 서비스 컨테이너).
 */
class RedisTokenAndAuthCodeIntegrationTest {

    private static LettuceConnectionFactory connectionFactory;
    private static RedisTemplate<String, String> redisTemplate;

    private RedisTokenService redisTokenService;
    private RedisAuthCodeService redisAuthCodeService;

    @BeforeAll
    static void setUpRedisTemplate() {
        connectionFactory = new LettuceConnectionFactory("localhost", 6379);
        connectionFactory.afterPropertiesSet();

        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new StringRedisSerializer());
        template.afterPropertiesSet();
        redisTemplate = template;
    }

    @AfterAll
    static void tearDownConnectionFactory() {
        connectionFactory.destroy();
    }

    @BeforeEach
    void setUp() {
        redisTokenService = new RedisTokenService(redisTemplate);
        redisAuthCodeService = new RedisAuthCodeService(redisTemplate);
    }

    @AfterEach
    void cleanUp() {
        redisTemplate.delete(List.of(
            "auth:refresh:9001",
            "auth:blacklist:sample-access-token",
            "auth:blacklist:other-access-token",
            "auth:email_code:test@teamfp.com",
            "auth:login_fail:test-login-id"
        ));
    }

    @Test
    @DisplayName("Refresh Token 저장/조회/검증 - 실제 Redis 왕복")
    void refreshToken_SaveGetValidate() {
        redisTokenService.saveRefreshToken(9001L, "sample-refresh-token");

        String stored = redisTokenService.getRefreshToken(9001L);

        assertThat(stored).isEqualTo("sample-refresh-token");
        assertThat(redisTokenService.isRefreshTokenValid(9001L, "sample-refresh-token")).isTrue();
        assertThat(redisTokenService.isRefreshTokenValid(9001L, "wrong-token")).isFalse();
    }

    @Test
    @DisplayName("Refresh Token TTL - 14일로 설정되는지 확인")
    void refreshToken_TtlIsFourteenDays() {
        redisTokenService.saveRefreshToken(9001L, "sample-refresh-token");

        Long ttl = redisTemplate.getExpire("auth:refresh:9001", TimeUnit.SECONDS);

        assertThat(ttl).isGreaterThan(Duration.ofDays(13).toSeconds());
        assertThat(ttl).isLessThanOrEqualTo(Duration.ofDays(14).toSeconds());
    }

    @Test
    @DisplayName("Refresh Token 삭제 - 로그아웃 시나리오")
    void refreshToken_Delete() {
        redisTokenService.saveRefreshToken(9001L, "sample-refresh-token");

        redisTokenService.deleteRefreshToken(9001L);

        assertThat(redisTokenService.getRefreshToken(9001L)).isNull();
    }

    @Test
    @DisplayName("Access Token 블랙리스트 등록/조회 - 남은 유효시간만큼 TTL 반영")
    void accessToken_Blacklist() {
        redisTokenService.blacklistAccessToken("sample-access-token", Duration.ofMinutes(5).toMillis());

        assertThat(redisTokenService.isBlacklisted("sample-access-token")).isTrue();
        assertThat(redisTokenService.isBlacklisted("other-access-token")).isFalse();
    }

    @Test
    @DisplayName("이메일 인증 코드 저장 후 올바른 코드로 검증하면 성공하고 키는 삭제된다")
    void emailCode_VerifySuccess_DeletesKey() {
        redisAuthCodeService.saveEmailCode("test@teamfp.com", "123456");

        boolean verified = redisAuthCodeService.verifyAndDeleteEmailCode("test@teamfp.com", "123456");

        assertThat(verified).isTrue();
        assertThat(redisTemplate.hasKey("auth:email_code:test@teamfp.com")).isFalse();
    }

    @Test
    @DisplayName("이메일 인증 코드가 틀리면 실패하고 키는 유지된다")
    void emailCode_VerifyFail_KeepsKey() {
        redisAuthCodeService.saveEmailCode("test@teamfp.com", "123456");

        boolean verified = redisAuthCodeService.verifyAndDeleteEmailCode("test@teamfp.com", "000000");

        assertThat(verified).isFalse();
        assertThat(redisTemplate.hasKey("auth:email_code:test@teamfp.com")).isTrue();
    }

    @Test
    @DisplayName("로그인 실패 카운터 - 5회 누적되면 잠금 상태가 된다")
    void loginFail_LocksAfterFiveAttempts() {
        for (int i = 1; i <= 4; i++) {
            redisAuthCodeService.incrementLoginFail("test-login-id");
        }
        assertThat(redisAuthCodeService.isLoginLocked("test-login-id")).isFalse();

        long count = redisAuthCodeService.incrementLoginFail("test-login-id");

        assertThat(count).isEqualTo(5);
        assertThat(redisAuthCodeService.isLoginLocked("test-login-id")).isTrue();
    }

    @Test
    @DisplayName("로그인 성공 시 실패 카운터 초기화")
    void loginFail_ResetOnSuccess() {
        redisAuthCodeService.incrementLoginFail("test-login-id");
        redisAuthCodeService.incrementLoginFail("test-login-id");

        redisAuthCodeService.resetLoginFail("test-login-id");

        assertThat(redisTemplate.hasKey("auth:login_fail:test-login-id")).isFalse();
    }
}
