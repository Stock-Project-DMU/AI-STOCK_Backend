package com.teamfp.aistock.infra.ls;

/**
 * LS증권 Open API 접근토큰 발급이 인증 실패(HTTP 401/403)로 거부됐을 때 던지는 예외.
 *
 * domain에 노출되는 비즈니스 예외가 아니라 infra 내부 재연결 로직 전용이므로
 * {@code CustomException}/{@code ErrorCode}를 쓰지 않는다. {@code LsReconnectService}가
 * 이 타입 여부로 "인증 실패로 의심되는 경우"와 "네트워크 문제로 의심되는 경우"를 구분해 로그를 남긴다.
 */
public class LsAuthenticationException extends RuntimeException {

    public LsAuthenticationException(String message, Throwable cause) {
        super(message, cause);
    }
}
