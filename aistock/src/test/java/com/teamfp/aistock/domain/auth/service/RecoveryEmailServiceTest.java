package com.teamfp.aistock.domain.auth.service;

import com.teamfp.aistock.domain.auth.dto.request.RecoveryEmailCodeRequest;
import com.teamfp.aistock.domain.auth.dto.request.RecoveryEmailCodeRequest.RecoveryPurpose;
import com.teamfp.aistock.domain.user.entity.User;
import com.teamfp.aistock.domain.user.repository.UserRepository;
import com.teamfp.aistock.global.redis.EmailVerificationPurpose;
import com.teamfp.aistock.global.redis.RedisAuthCodeService;
import com.teamfp.aistock.infra.mail.MailClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RecoveryEmailServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private RedisAuthCodeService redisAuthCodeService;
    @Mock private MailClient mailClient;
    @InjectMocks private RecoveryEmailService recoveryEmailService;

    @Test
    void unmatchedIdentityDoesNotSendCode() {
        var request = new RecoveryEmailCodeRequest(RecoveryPurpose.FIND_ID,
                null, "다른 이름", "tester01@example.com", LocalDate.of(2000, 1, 1));
        var user = User.builder().loginId("tester01").name("테스터").email(request.email())
                .birthdate(request.birthdate()).isActive(true).build();
        given(userRepository.findByEmail(request.email())).willReturn(Optional.of(user));

        recoveryEmailService.sendCode(request);

        verify(redisAuthCodeService, never()).tryStartEmailSend(anyString());
        verify(mailClient, never()).sendAuthCode(anyString(), anyString());
    }

    @Test
    void validPasswordRecoverySendsPurposeSpecificCode() {
        var request = new RecoveryEmailCodeRequest(RecoveryPurpose.RESET_PASSWORD,
                "tester01", "테스터", "tester01@example.com", null);
        var user = User.builder().loginId(request.loginId()).name(request.name())
                .email(request.email()).password("oldHash").isActive(true).build();
        given(userRepository.findByEmail(request.email())).willReturn(Optional.of(user));
        given(redisAuthCodeService.tryStartEmailSend(request.email())).willReturn(true);

        recoveryEmailService.sendCode(request);

        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(redisAuthCodeService).saveEmailCode(eq(request.email()), code.capture(),
                eq(EmailVerificationPurpose.RESET_PASSWORD));
        assertThat(code.getValue()).matches("\\d{6}");
        verify(mailClient).sendAuthCode(request.email(), code.getValue());
    }

    @Test
    void validFindIdRecoverySendsPurposeSpecificCode() {
        var request = new RecoveryEmailCodeRequest(RecoveryPurpose.FIND_ID,
                null, "테스터", "tester01@example.com", LocalDate.of(2000, 1, 1));
        var user = User.builder().loginId("tester01").name(request.name()).email(request.email())
                .birthdate(request.birthdate()).isActive(true).build();
        given(userRepository.findByEmail(request.email())).willReturn(Optional.of(user));
        given(redisAuthCodeService.tryStartEmailSend(request.email())).willReturn(true);

        recoveryEmailService.sendCode(request);

        verify(redisAuthCodeService).saveEmailCode(eq(request.email()), anyString(),
                eq(EmailVerificationPurpose.FIND_ID));
        verify(mailClient).sendAuthCode(eq(request.email()), anyString());
    }

    @Test
    void cooldownSkipsAnotherMail() {
        var request = new RecoveryEmailCodeRequest(RecoveryPurpose.RESET_PASSWORD,
                "tester01", "테스터", "tester01@example.com", null);
        var user = User.builder().loginId(request.loginId()).name(request.name())
                .email(request.email()).password("oldHash").isActive(true).build();
        given(userRepository.findByEmail(request.email())).willReturn(Optional.of(user));

        recoveryEmailService.sendCode(request);

        verify(redisAuthCodeService, never()).saveEmailCode(anyString(), anyString(), any());
        verify(mailClient, never()).sendAuthCode(anyString(), anyString());
    }
}
