package com.teamfp.aistock.global.redis;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
@RequiredArgsConstructor
public class RedisTokenService {

    private final RedisTemplate<String, String> redisTemplate;

    private static final String REFRESH_KEY   = "auth:refresh:";
    private static final String BLACKLIST_KEY = "auth:blacklist:";

    private static final long REFRESH_TTL_DAYS = 14;

    // Refresh Token 저장
    public void saveRefreshToken(Long userId, String refreshToken) {
        redisTemplate.opsForValue().set(
            REFRESH_KEY + userId,
            refreshToken,
            Duration.ofDays(REFRESH_TTL_DAYS)
        );
    }

    // Refresh Token 조회
    public String getRefreshToken(Long userId) {
        return redisTemplate.opsForValue().get(REFRESH_KEY + userId);
    }

    // Refresh Token 검증
    public boolean isRefreshTokenValid(Long userId, String refreshToken) {
        String stored = getRefreshToken(userId);
        return stored != null && stored.equals(refreshToken);
    }

    // Refresh Token 삭제 (로그아웃)
    public void deleteRefreshToken(Long userId) {
        redisTemplate.delete(REFRESH_KEY + userId);
    }

    // Access Token 블랙리스트 등록 (로그아웃 시)
    // TTL은 고정값이 아니라 Access Token의 실제 남은 유효시간(remainingMillis)을 사용한다.
    public void blacklistAccessToken(String accessToken, long remainingMillis) {
        redisTemplate.opsForValue().set(
            BLACKLIST_KEY + accessToken,
            "LOGOUT",
            Duration.ofMillis(remainingMillis)
        );
    }

    // 블랙리스트 여부 확인
    public boolean isBlacklisted(String accessToken) {
        return Boolean.TRUE.equals(
            redisTemplate.hasKey(BLACKLIST_KEY + accessToken)
        );
    }
}
