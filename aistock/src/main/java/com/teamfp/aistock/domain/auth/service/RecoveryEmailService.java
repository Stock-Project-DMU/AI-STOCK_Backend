package com.teamfp.aistock.domain.auth.service;

import com.teamfp.aistock.domain.auth.dto.request.RecoveryEmailCodeRequest;
import com.teamfp.aistock.domain.user.entity.User;
import com.teamfp.aistock.domain.user.repository.UserRepository;
import com.teamfp.aistock.global.redis.EmailVerificationPurpose;
import com.teamfp.aistock.global.redis.RedisAuthCodeService;
import com.teamfp.aistock.infra.mail.MailClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecoveryEmailService {

    private static final SecureRandom CODE_RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final RedisAuthCodeService redisAuthCodeService;
    private final MailClient mailClient;

    // 조회부터 발송까지 응답 스레드 밖에서 처리해 계정 존재 여부와 메일 전송 시간에 따른 차이를 숨긴다.
    @Async("mailTaskExecutor")
    public void sendCode(RecoveryEmailCodeRequest request) {
        if (request.purpose() == null || request.email() == null || request.name() == null) {
            return;
        }
        boolean matches = userRepository.findByEmail(request.email())
                .filter(User::isActive)
                .filter(user -> request.name().equals(user.getName()) && user.getLoginId() != null)
                .filter(user -> switch (request.purpose()) {
                    case FIND_ID -> request.birthdate() != null
                            && request.birthdate().equals(user.getBirthdate());
                    case RESET_PASSWORD -> request.loginId() != null
                            && request.loginId().equals(user.getLoginId()) && user.getPassword() != null;
                })
                .isPresent();
        if (!matches || !redisAuthCodeService.tryStartEmailSend(request.email())) {
            return;
        }

        String code = String.format("%06d", CODE_RANDOM.nextInt(1_000_000));
        EmailVerificationPurpose purpose = EmailVerificationPurpose.valueOf(request.purpose().name());
        redisAuthCodeService.saveEmailCode(request.email(), code, purpose);
        try {
            mailClient.sendAuthCode(request.email(), code);
        } catch (RuntimeException exception) {
            log.error("계정 복구 인증 메일 발송 실패", exception);
        }
    }
}
