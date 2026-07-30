package com.teamfp.aistock.global.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.teamfp.aistock.global.response.ApiResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * feature/mypage-profit 코드리뷰 대응 — GET /api/orders?accountId=... 처럼 필수 쿼리
 * 파라미터가 누락되거나 타입이 안 맞는 요청이 catch-all Exception 핸들러에 가로채여
 * 500으로 나가던 문제를 400으로 고친 두 핸들러의 단위 테스트.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("필수 쿼리 파라미터 누락 시 500이 아니라 400으로 응답한다")
    void handleMissingServletRequestParameterException_returns400() {
        MissingServletRequestParameterException exception =
                new MissingServletRequestParameterException("accountId", "Long");

        ResponseEntity<ApiResponse<Void>> response = handler.handleMissingServletRequestParameterException(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().isSuccess()).isFalse();
    }

    @Test
    @DisplayName("쿼리 파라미터 타입 불일치(accountId=abc) 시 500이 아니라 400으로 응답한다")
    void handleMethodArgumentTypeMismatchException_returns400() throws NoSuchMethodException {
        MethodParameter methodParameter = new MethodParameter(
                GlobalExceptionHandlerTest.class.getDeclaredMethod("dummyAccountIdParam", Long.class), 0);
        MethodArgumentTypeMismatchException exception =
                new MethodArgumentTypeMismatchException("abc", Long.class, "accountId", methodParameter, new NumberFormatException("abc"));

        ResponseEntity<ApiResponse<Void>> response = handler.handleMethodArgumentTypeMismatchException(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().isSuccess()).isFalse();
    }

    // MethodArgumentTypeMismatchException 생성에 필요한 MethodParameter를 만들기 위한 더미 메서드
    @SuppressWarnings("unused")
    private void dummyAccountIdParam(Long accountId) {
    }
}
