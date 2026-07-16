package com.teamfp.aistock.global.redis;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
@RequiredArgsConstructor
public class RedisAuthCodeService {

    private final RedisTemplate<String, String> redisTemplate;

    private static final String EMAIL_CODE_KEY = "auth:email_code:";
    private static final String LOGIN_FAIL_KEY = "auth:login_fail:";

    private static final long EMAIL_CODE_TTL_MINUTES = 5;
    private static final long LOGIN_FAIL_TTL_MINUTES = 10;
    private static final int  MAX_LOGIN_FAIL          = 5;

    // 이메일 인증 코드 저장
    public void saveEmailCode(String email, String code) {
        redisTemplate.opsForValue().set(
            EMAIL_CODE_KEY + email,
            code,
            Duration.ofMinutes(EMAIL_CODE_TTL_MINUTES)
        );
    }

    // 이메일 인증 코드 검증 후 삭제
    public boolean verifyAndDeleteEmailCode(String email, String inputCode) {
        String key = EMAIL_CODE_KEY + email;
        String stored = redisTemplate.opsForValue().get(key);
        if (stored != null && stored.equals(inputCode)) {
            redisTemplate.delete(key);
            return true;
        }
        return false;
    }

    // 로그인 실패 횟수 증가
    public long incrementLoginFail(String loginId) {
        String key = LOGIN_FAIL_KEY + loginId;
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1) {
            redisTemplate.expire(key, Duration.ofMinutes(LOGIN_FAIL_TTL_MINUTES));
        }
        return count != null ? count : 1;
    }

    // 로그인 잠금 여부 확인
    public boolean isLoginLocked(String loginId) {
        String count = redisTemplate.opsForValue().get(LOGIN_FAIL_KEY + loginId);
        return count != null && Integer.parseInt(count) >= MAX_LOGIN_FAIL;
    }

    // 로그인 성공 시 실패 카운터 초기화
    public void resetLoginFail(String loginId) {
        redisTemplate.delete(LOGIN_FAIL_KEY + loginId);
    }
}
