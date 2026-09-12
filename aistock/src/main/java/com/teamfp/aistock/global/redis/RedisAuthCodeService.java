package com.teamfp.aistock.global.redis;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
@RequiredArgsConstructor
public class RedisAuthCodeService {

    private final RedisTemplate<String, String> redisTemplate;

    private static final String EMAIL_CODE_KEY     = "auth:email_code:";
    private static final String EMAIL_VERIFIED_KEY = "auth:email_verified:";
    private static final String LOGIN_FAIL_KEY     = "auth:login_fail:";

    private static final long EMAIL_CODE_TTL_MINUTES     = 5;
    private static final long EMAIL_VERIFIED_TTL_MINUTES = 30;
    private static final long LOGIN_FAIL_TTL_MINUTES     = 10;
    private static final int  MAX_LOGIN_FAIL             = 5;
    private static final org.springframework.data.redis.core.script.DefaultRedisScript<Long> VERIFY_EMAIL_SCRIPT =
            new org.springframework.data.redis.core.script.DefaultRedisScript<>("""
                local failures = tonumber(redis.call('GET', KEYS[2]) or '0')
                if failures >= 5 then return 0 end
                local stored = redis.call('GET', KEYS[1])
                if stored and stored == ARGV[1] then
                    redis.call('DEL', KEYS[1])
                    redis.call('DEL', KEYS[2])
                    return 1
                end
                local count = redis.call('INCR', KEYS[2])
                if count == 1 then redis.call('EXPIRE', KEYS[2], 600) end
                return 0
                """, Long.class);

    // 이메일 인증 코드 저장
    public void saveEmailCode(String email, String code) {
        redisTemplate.opsForValue().set(
            EMAIL_CODE_KEY + email.toLowerCase(java.util.Locale.ROOT),
            code,
            Duration.ofMinutes(EMAIL_CODE_TTL_MINUTES)
        );
    }

    // 이메일 인증 코드 검증 후 삭제
    public boolean verifyAndDeleteEmailCode(String email, String inputCode) {
        String normalized = email.toLowerCase(java.util.Locale.ROOT);
        return Long.valueOf(1L).equals(redisTemplate.execute(VERIFY_EMAIL_SCRIPT,
                java.util.List.of(EMAIL_CODE_KEY + normalized, LOGIN_FAIL_KEY + "email:" + normalized), inputCode));
    }

    // 이메일 인증 성공 마킹 (verifyAndDeleteEmailCode() 성공 직후 호출) — 회원가입 폼 작성 시간을
    // 고려해 인증코드(5분)보다 긴 30분 TTL을 둔다
    public void markEmailVerified(String email) {
        redisTemplate.opsForValue().set(
            EMAIL_VERIFIED_KEY + email.toLowerCase(java.util.Locale.ROOT),
            "true",
            Duration.ofMinutes(EMAIL_VERIFIED_TTL_MINUTES)
        );
    }

    // 이메일 인증 여부 확인 후 소비(삭제) — 같은 인증을 여러 회원가입에 재사용하지 못하도록 1회용으로 처리
    public boolean consumeEmailVerified(String email) {
        return redisTemplate.opsForValue().getAndDelete(EMAIL_VERIFIED_KEY + email.toLowerCase(java.util.Locale.ROOT)) != null;
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
