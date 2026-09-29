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
    private static final String EMAIL_SEND_COOLDOWN_KEY = "auth:email_send_cooldown:";
    private static final String LOGIN_FAIL_KEY     = "auth:login_fail:";

    private static final long EMAIL_CODE_TTL_MINUTES     = 5;
    private static final long EMAIL_VERIFIED_TTL_MINUTES = 30;
    private static final long EMAIL_SEND_COOLDOWN_SECONDS = 60;
    private static final long LOGIN_FAIL_TTL_MINUTES     = 10;
    private static final int  MAX_LOGIN_FAIL             = 5;
    private static final org.springframework.data.redis.core.script.DefaultRedisScript<Long> VERIFY_EMAIL_SCRIPT =
            new org.springframework.data.redis.core.script.DefaultRedisScript<>("""
                local failures = tonumber(redis.call('GET', KEYS[4]) or '0')
                if failures >= 5 then return 0 end
                local matches = {}
                for i = 1, 3 do
                    if redis.call('GET', KEYS[i]) == ARGV[1] then
                        table.insert(matches, i)
                    end
                end
                if #matches > 1 then
                    for _, i in ipairs(matches) do redis.call('DEL', KEYS[i]) end
                    return 0
                end
                if #matches == 1 then
                    redis.call('DEL', KEYS[matches[1]])
                    redis.call('DEL', KEYS[4])
                    return matches[1]
                end
                local count = redis.call('INCR', KEYS[4])
                if count == 1 then redis.call('EXPIRE', KEYS[4], 600) end
                return 0
                """, Long.class);

    private String normalizedEmail(String email) {
        return email.toLowerCase(java.util.Locale.ROOT);
    }

    private String codeKey(String email, EmailVerificationPurpose purpose) {
        return EMAIL_CODE_KEY + purpose.name().toLowerCase(java.util.Locale.ROOT) + ":" + normalizedEmail(email);
    }

    private String verifiedKey(String email, EmailVerificationPurpose purpose) {
        return EMAIL_VERIFIED_KEY + purpose.name().toLowerCase(java.util.Locale.ROOT) + ":" + normalizedEmail(email);
    }

    // SET NX와 만료 시간을 함께 사용해 동시 요청에서도 이메일별 발송 간격을 지킨다.
    public boolean tryStartEmailSend(String email) {
        return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(
                EMAIL_SEND_COOLDOWN_KEY + normalizedEmail(email), "1",
                Duration.ofSeconds(EMAIL_SEND_COOLDOWN_SECONDS)));
    }

    // 이메일 인증 코드 저장
    public void saveEmailCode(String email, String code) {
        saveEmailCode(email, code, EmailVerificationPurpose.SIGNUP);
    }

    public void saveEmailCode(String email, String code, EmailVerificationPurpose purpose) {
        redisTemplate.opsForValue().set(
            codeKey(email, purpose),
            code,
            Duration.ofMinutes(EMAIL_CODE_TTL_MINUTES)
        );
    }

    // 이메일 인증 코드 검증 후 삭제
    public EmailVerificationPurpose verifyAndDeleteEmailCode(String email, String inputCode) {
        String normalized = normalizedEmail(email);
        Long result = redisTemplate.execute(VERIFY_EMAIL_SCRIPT,
                java.util.List.of(
                        codeKey(email, EmailVerificationPurpose.SIGNUP),
                        codeKey(email, EmailVerificationPurpose.FIND_ID),
                        codeKey(email, EmailVerificationPurpose.RESET_PASSWORD),
                        LOGIN_FAIL_KEY + "email:" + normalized), inputCode);
        if (result == null || result < 1 || result > EmailVerificationPurpose.values().length) {
            return null;
        }
        return EmailVerificationPurpose.values()[result.intValue() - 1];
    }

    // 이메일 인증 성공 마킹 (verifyAndDeleteEmailCode() 성공 직후 호출) — 후속 입력 시간을
    // 고려해 인증코드(5분)보다 긴 30분 TTL을 둔다
    public void markEmailVerified(String email) {
        markEmailVerified(email, EmailVerificationPurpose.SIGNUP);
    }

    public void markEmailVerified(String email, EmailVerificationPurpose purpose) {
        redisTemplate.opsForValue().set(
            verifiedKey(email, purpose),
            "true",
            Duration.ofMinutes(EMAIL_VERIFIED_TTL_MINUTES)
        );
    }

    public boolean isEmailVerified(String email, EmailVerificationPurpose purpose) {
        return redisTemplate.opsForValue().get(verifiedKey(email, purpose)) != null;
    }

    // 이메일 인증 여부 확인 후 소비(삭제) — 같은 인증을 여러 회원가입에 재사용하지 못하도록 1회용으로 처리
    public boolean consumeEmailVerified(String email) {
        return consumeEmailVerified(email, EmailVerificationPurpose.SIGNUP);
    }

    public boolean consumeEmailVerified(String email, EmailVerificationPurpose purpose) {
        return redisTemplate.opsForValue().getAndDelete(verifiedKey(email, purpose)) != null;
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
