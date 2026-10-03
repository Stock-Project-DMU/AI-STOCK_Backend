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
    EMAIL_NOT_VERIFIED(HttpStatus.BAD_REQUEST, "EMAIL_NOT_VERIFIED", "이메일 인증이 필요합니다."),
    EMAIL_SEND_COOLDOWN(HttpStatus.TOO_MANY_REQUESTS, "EMAIL_SEND_COOLDOWN", "인증번호를 다시 요청하기 전에 잠시 기다려 주세요."),
    RECOVERY_INFO_MISMATCH(HttpStatus.BAD_REQUEST, "RECOVERY_INFO_MISMATCH", "입력한 회원 정보가 일치하지 않습니다."),
    INSUFFICIENT_BALANCE(HttpStatus.BAD_REQUEST, "INSUFFICIENT_BALANCE", "계좌 잔액이 부족합니다."),
    INSUFFICIENT_HOLDING(HttpStatus.BAD_REQUEST, "INSUFFICIENT_HOLDING", "보유 주식이 부족합니다."),
    ACCOUNT_SUSPENDED(HttpStatus.BAD_REQUEST, "ACCOUNT_SUSPENDED", "정지된 계좌는 주문할 수 없습니다."),
    ACCOUNT_SUSPENDED_CHARGE(HttpStatus.BAD_REQUEST, "ACCOUNT_SUSPENDED_CHARGE", "거래가 정지된 계좌는 충전할 수 없습니다."),
    INVALID_ADMIN_CODE(HttpStatus.BAD_REQUEST, "INVALID_ADMIN_CODE", "관리자 코드가 일치하지 않습니다."),
    ACCOUNT_LIMIT_EXCEEDED(HttpStatus.BAD_REQUEST, "ACCOUNT_LIMIT_EXCEEDED", "계좌는 1개만 만들 수 있습니다."),
    CHARGE_LIMIT_EXCEEDED(HttpStatus.BAD_REQUEST, "CHARGE_LIMIT_EXCEEDED", "직접 충전 가능 횟수(3회)를 모두 사용했습니다. 충전 요청으로 관리자에게 요청해 주세요."),
    DEPOSIT_LIMIT_EXCEEDED(HttpStatus.BAD_REQUEST, "DEPOSIT_LIMIT_EXCEEDED", "계좌 예치금은 최대 1조원까지 보유할 수 있습니다."),
    CHARGE_REQUEST_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "CHARGE_REQUEST_NOT_ALLOWED", "직접 충전 가능 횟수가 남아 있습니다. 관리자 요청 없이 바로 충전해 주세요."),
    SELF_STATUS_CHANGE_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "SELF_STATUS_CHANGE_NOT_ALLOWED", "본인 계정은 정지할 수 없습니다."),
    LAST_ADMIN_SUSPEND_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "LAST_ADMIN_SUSPEND_NOT_ALLOWED", "마지막 남은 관리자는 정지할 수 없습니다."),
    INVALID_NEWS_OUTLET(HttpStatus.BAD_REQUEST, "INVALID_NEWS_OUTLET", "지원하지 않는 언론사입니다."),
    CHARGE_REQUEST_ALREADY_PENDING(HttpStatus.BAD_REQUEST, "CHARGE_REQUEST_ALREADY_PENDING", "이미 처리 대기 중인 충전 요청이 있습니다."),
    GOAL_TEXT_PARSE_FAILED(HttpStatus.BAD_REQUEST, "GOAL_TEXT_PARSE_FAILED", "목표를 이해하지 못했어요. 목표 금액이 들어간 문장으로 다시 입력해 주세요. 예) \"3년 안에 5천만원 모으기\", \"총자산 1억 만들기\""),
    GOAL_PERIOD_OUT_OF_RANGE(HttpStatus.BAD_REQUEST, "GOAL_PERIOD_OUT_OF_RANGE", "목표 기한은 1개월 이상 30년 이하로 입력해 주세요."),

    // 401 Unauthorized
    INVALID_PASSWORD(HttpStatus.UNAUTHORIZED, "INVALID_PASSWORD", "비밀번호가 일치하지 않습니다."),
    PASSWORD_NOT_SET(HttpStatus.UNAUTHORIZED, "PASSWORD_NOT_SET", "소셜 로그인 계정은 비밀번호가 설정되어 있지 않습니다."),
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "유효하지 않은 토큰입니다."),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "TOKEN_EXPIRED", "만료된 토큰입니다."),
    REFRESH_TOKEN_NOT_FOUND(HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_NOT_FOUND", "리프레시 토큰을 찾을 수 없습니다."),

    // 403 Forbidden
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "접근 권한이 없습니다."),
    SURVEY_REQUIRED(HttpStatus.FORBIDDEN, "SURVEY_REQUIRED", "AI 상담을 이용하려면 투자 성향 설문을 완료해 주세요."),
    INVESTMENT_PROFILE_REQUIRED(HttpStatus.FORBIDDEN, "INVESTMENT_PROFILE_REQUIRED", "목표 도달 시뮬레이션을 이용하려면 투자 성향 설문을 먼저 완료해 주세요."),

    // 404 Not Found
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "사용자를 찾을 수 없습니다."),
    ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "계좌를 찾을 수 없습니다."),
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "주문을 찾을 수 없습니다."),
    STOCK_NOT_FOUND(HttpStatus.NOT_FOUND, "STOCK_NOT_FOUND", "주식 종목을 찾을 수 없습니다."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "요청한 API 경로를 찾을 수 없습니다."),
    INQUIRY_NOT_FOUND(HttpStatus.NOT_FOUND, "INQUIRY_NOT_FOUND", "문의를 찾을 수 없습니다."),
    CHARGE_REQUEST_NOT_FOUND(HttpStatus.NOT_FOUND, "CHARGE_REQUEST_NOT_FOUND", "충전 요청을 찾을 수 없습니다."),
    AUDIT_LOG_NOT_FOUND(HttpStatus.NOT_FOUND, "AUDIT_LOG_NOT_FOUND", "감사 로그를 찾을 수 없습니다."),
    NOTIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "NOTIFICATION_NOT_FOUND", "알림을 찾을 수 없습니다."),
    AI_SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "AI_SESSION_NOT_FOUND", "AI 상담 세션을 찾을 수 없습니다."),
    SIMULATION_NOT_FOUND(HttpStatus.NOT_FOUND, "SIMULATION_NOT_FOUND", "시뮬레이션을 찾을 수 없습니다."),
    SIMULATION_EXPIRED(HttpStatus.NOT_FOUND, "SIMULATION_EXPIRED", "시뮬레이션 결과 보관 시간(30분)이 지났습니다. 다시 실행한 뒤 저장해 주세요."),
    NEWS_BRIEFING_NOT_FOUND(HttpStatus.NOT_FOUND, "NEWS_BRIEFING_NOT_FOUND", "아직 생성된 브리핑이 없습니다."),
    NEWS_CHAT_SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "NEWS_CHAT_SESSION_NOT_FOUND", "뉴스 채팅을 찾을 수 없습니다."),

    // 409 Conflict
    DUPLICATE_LOGIN_ID(HttpStatus.CONFLICT, "DUPLICATE_LOGIN_ID", "이미 존재하는 아이디입니다."),
    DUPLICATE_EMAIL(HttpStatus.CONFLICT, "DUPLICATE_EMAIL", "이미 존재하는 이메일입니다."),
    OPTIMISTIC_LOCK_CONFLICT(HttpStatus.CONFLICT, "OPTIMISTIC_LOCK_CONFLICT", "동시 요청으로 처리에 실패했습니다. 다시 시도해 주세요."),
    ORDER_ALREADY_PROCESSED(HttpStatus.CONFLICT, "ORDER_ALREADY_PROCESSED", "이미 체결되었거나 취소된 주문입니다."),
    CHARGE_REQUEST_ALREADY_PROCESSED(HttpStatus.CONFLICT, "CHARGE_REQUEST_ALREADY_PROCESSED", "이미 승인되었거나 거절된 충전 요청입니다."),
    SIMULATION_ALREADY_SAVED(HttpStatus.CONFLICT, "SIMULATION_ALREADY_SAVED", "이미 저장한 시뮬레이션 결과입니다. 저장 목록에서 확인해 주세요."),

    // 423 Locked
    LOGIN_LOCKED(HttpStatus.LOCKED, "LOGIN_LOCKED", "로그인 시도 횟수 초과로 계정이 잠겼습니다."),
    USER_SUSPENDED(HttpStatus.LOCKED, "USER_SUSPENDED", "관리자에 의해 정지된 계정입니다."),

    // 429 Too Many Requests
    GEMINI_RATE_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "GEMINI_RATE_LIMIT_EXCEEDED", "AI 서비스 요청 제한을 초과했습니다."),
    DART_RATE_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "DART_RATE_LIMIT_EXCEEDED", "공시 데이터 요청 제한을 초과했습니다. 잠시 후 다시 시도해 주세요."),

    // 500 Internal Server Error
    REDIS_SERIALIZATION_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "REDIS_SERIALIZATION_ERROR", "Redis 데이터 직렬화 에러가 발생했습니다."),
    SCENARIO_DATA_PARSE_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "SCENARIO_DATA_PARSE_ERROR", "시나리오 데이터 파싱 중 오류가 발생했습니다."),
    NEWS_SOURCE_DATA_PARSE_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "NEWS_SOURCE_DATA_PARSE_ERROR", "뉴스 브리핑 근거 기사 데이터 파싱 중 오류가 발생했습니다."),
    NEWS_CHAT_DATA_PARSE_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "NEWS_CHAT_DATA_PARSE_ERROR", "뉴스 채팅 데이터를 읽지 못했습니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR", "서버 내부 에러가 발생했습니다."),

    // 502 Bad Gateway
    EXTERNAL_API_ERROR(HttpStatus.BAD_GATEWAY, "EXTERNAL_API_ERROR", "외부 API 연동 중 에러가 발생했습니다."),
    OAUTH_PROVIDER_RESPONSE_INVALID(HttpStatus.BAD_GATEWAY, "OAUTH_PROVIDER_RESPONSE_INVALID", "소셜 로그인 제공자의 응답이 올바르지 않습니다."),
    REBALANCE_SUGGESTION_INVALID(HttpStatus.BAD_GATEWAY, "REBALANCE_SUGGESTION_INVALID", "AI 리밸런싱 추천 결과가 올바르지 않습니다. 잠시 후 다시 시도해 주세요."),

    // 503 Service Unavailable
    MARKET_NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE, "MARKET_NOT_CONFIGURED", "시장 데이터 제공자 설정이 아직 완료되지 않았습니다."),
    OAUTH_NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE, "OAUTH_NOT_CONFIGURED", "소셜 로그인 제공자 설정이 아직 완료되지 않았습니다."),
    // 외부 시세 데이터 제공사 REST 호출(토큰 발급 포함) 자체가 실패한 경우 — "조회는 성공했지만 데이터가
    // 없음"(빈 목록)과 구분하기 위해 EXTERNAL_API_ERROR와 별도로 둔다(외부 장애와 빈 목록 구분 처리, #05).
    MARKET_DATA_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "MARKET_DATA_UNAVAILABLE", "시세 데이터 제공사와 일시적으로 연결할 수 없습니다. 잠시 후 다시 시도해 주세요."),
    STOCK_PRICE_NOT_AVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "STOCK_PRICE_NOT_AVAILABLE", "현재가 정보를 일시적으로 가져올 수 없습니다. 잠시 후 다시 시도해 주세요.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
