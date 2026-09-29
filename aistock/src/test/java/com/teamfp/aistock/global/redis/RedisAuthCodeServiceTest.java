package com.teamfp.aistock.global.redis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RedisAuthCodeServiceTest {

    @Mock private RedisTemplate<String, String> redisTemplate;
    @Mock private ValueOperations<String, String> values;
    @InjectMocks private RedisAuthCodeService service;

    @Test
    void cooldownUsesAtomicSetIfAbsentPerEmail() {
        given(redisTemplate.opsForValue()).willReturn(values);
        given(values.setIfAbsent("auth:email_send_cooldown:user@example.com", "1", Duration.ofSeconds(60)))
                .willReturn(true);

        assertThat(service.tryStartEmailSend("User@Example.com")).isTrue();
        verify(values).setIfAbsent("auth:email_send_cooldown:user@example.com", "1", Duration.ofSeconds(60));
    }

    @Test
    void codesAndVerificationMarkersAreSeparatedByPurpose() {
        given(redisTemplate.opsForValue()).willReturn(values);
        service.saveEmailCode("User@Example.com", "123456", EmailVerificationPurpose.FIND_ID);
        service.markEmailVerified("User@Example.com", EmailVerificationPurpose.FIND_ID);

        verify(values).set("auth:email_code:find_id:user@example.com", "123456", Duration.ofMinutes(5));
        verify(values).set("auth:email_verified:find_id:user@example.com", "true", Duration.ofMinutes(30));
    }

    @Test
    void verificationReturnsMatchedPurpose() {
        given(redisTemplate.execute(any(RedisScript.class), anyList(), eq("123456"))).willReturn(3L);

        assertThat(service.verifyAndDeleteEmailCode("User@Example.com", "123456"))
                .isEqualTo(EmailVerificationPurpose.RESET_PASSWORD);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(redisTemplate).execute(any(RedisScript.class), keys.capture(), eq("123456"));
        assertThat(keys.getValue()).containsExactly(
                "auth:email_code:signup:user@example.com",
                "auth:email_code:find_id:user@example.com",
                "auth:email_code:reset_password:user@example.com",
                "auth:login_fail:email:user@example.com");
    }
}
