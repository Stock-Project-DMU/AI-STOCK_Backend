package com.teamfp.aistock.infra.mail;

import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MailClient {

    private final JavaMailSender mailSender;

    @Value("${app.mail.from}")
    private String fromAddress;

    private static final String AUTH_CODE_SUBJECT = "[AI STOCK] 이메일 인증코드 안내";

    // 회원가입 이메일 인증코드 발송
    public void sendAuthCode(String toEmail, String code) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(toEmail);
        message.setSubject(AUTH_CODE_SUBJECT);
        message.setText("AI STOCK 이메일 인증코드입니다.\n\n인증코드: " + code + "\n\n인증코드는 5분간 유효합니다. 요청하지 않았다면 이 메일을 무시해 주세요.");

        try {
            mailSender.send(message);
        } catch (MailException e) {
            log.error("이메일 인증코드 발송 실패 - to: {}", toEmail, e);
            throw new CustomException(ErrorCode.EXTERNAL_API_ERROR);
        }
    }
}
