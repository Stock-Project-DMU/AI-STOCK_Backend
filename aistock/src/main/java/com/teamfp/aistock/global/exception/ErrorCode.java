package com.teamfp.aistock.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // 400 Bad Request
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "올바르지 않은 입력값입니다."),
    EMAIL_CODE_MISMATCH(HttpStatus.BAD_REQUEST, "EMAIL_CODE_MISMATCH", "이메일 인증 코드가 일치하지 않습니다."),
    EMAIL_CODE_EXPIRED(HttpStatus.BAD_REQUEST, "EMAIL_CODE_EXPIRED", "이메일 인증 코드가 만료되었습니다."),
    INSUFFICIENT_BALANCE(HttpStatus.BAD_REQUEST, "INSUFFICIENT_BALANCE", "계좌 잔액이 부족합니다."),
    INSUFFICIENT_HOLDING(HttpStatus.BAD_REQUEST, "INSUFFICIENT_HOLDING", "보유 주식이 부족합니다."),
    ACCOUNT_SUSPENDED(HttpStatus.BAD_REQUEST, "ACCOUNT_SUSPENDED", "정지된 계좌는 주문할 수 없습니다."),
    INVALID_ADMIN_CODE(HttpStatus.BAD_REQUEST, "INVALID_ADMIN_CODE", "관리자 코드가 일치하지 않습니다."),
    ACCOUNT_LIMIT_EXCEEDED(HttpStatus.BAD_REQUEST, "ACCOUNT_LIMIT_EXCEEDED", "계좌는 최대 3개까지 만들 수 있습니다."),
    CHARGE_LIMIT_EXCEEDED(HttpStatus.BAD_REQUEST, "CHARGE_LIMIT_EXCEEDED", "자동 충전 가능 횟수(3회)를 초과했습니다. 관리자에게 문의해 주세요."),

    // 401 Unauthorized
    INVALID_PASSWORD(HttpStatus.UNAUTHORIZED, "INVALID_PASSWORD", "비밀번호가 일치하지 않습니다."),
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "유효하지 않은 토큰입니다."),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "TOKEN_EXPIRED", "만료된 토큰입니다."),
    REFRESH_TOKEN_NOT_FOUND(HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_NOT_FOUND", "리프레시 토큰을 찾을 수 없습니다."),

    // 403 Forbidden
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "접근 권한이 없습니다."),

    // 404 Not Found
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "사용자를 찾을 수 없습니다."),
    ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "계좌를 찾을 수 없습니다."),
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "주문을 찾을 수 없습니다."),
    STOCK_NOT_FOUND(HttpStatus.NOT_FOUND, "STOCK_NOT_FOUND", "주식 종목을 찾을 수 없습니다."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "요청한 API 경로를 찾을 수 없습니다."),
    INQUIRY_NOT_FOUND(HttpStatus.NOT_FOUND, "INQUIRY_NOT_FOUND", "문의를 찾을 수 없습니다."),

    // 409 Conflict
    DUPLICATE_LOGIN_ID(HttpStatus.CONFLICT, "DUPLICATE_LOGIN_ID", "이미 존재하는 아이디입니다."),
    DUPLICATE_EMAIL(HttpStatus.CONFLICT, "DUPLICATE_EMAIL", "이미 존재하는 이메일입니다."),
    OPTIMISTIC_LOCK_CONFLICT(HttpStatus.CONFLICT, "OPTIMISTIC_LOCK_CONFLICT", "동시 요청으로 처리에 실패했습니다. 다시 시도해 주세요."),
    ORDER_ALREADY_PROCESSED(HttpStatus.CONFLICT, "ORDER_ALREADY_PROCESSED", "이미 체결되었거나 취소된 주문입니다."),

    // 423 Locked
    LOGIN_LOCKED(HttpStatus.LOCKED, "LOGIN_LOCKED", "로그인 시도 횟수 초과로 계정이 잠겼습니다."),
    USER_SUSPENDED(HttpStatus.LOCKED, "USER_SUSPENDED", "관리자에 의해 정지된 계정입니다."),

    // 429 Too Many Requests
    GEMINI_RATE_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "GEMINI_RATE_LIMIT_EXCEEDED", "AI 서비스 요청 제한을 초과했습니다."),

    // 500 Internal Server Error
    REDIS_SERIALIZATION_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "REDIS_SERIALIZATION_ERROR", "Redis 데이터 직렬화 에러가 발생했습니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR", "서버 내부 에러가 발생했습니다."),

    // 502 Bad Gateway
    EXTERNAL_API_ERROR(HttpStatus.BAD_GATEWAY, "EXTERNAL_API_ERROR", "외부 API 연동 중 에러가 발생했습니다."),

    // 503 Service Unavailable
    STOCK_PRICE_NOT_AVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "STOCK_PRICE_NOT_AVAILABLE", "현재가 정보를 일시적으로 가져올 수 없습니다. 잠시 후 다시 시도해 주세요.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
