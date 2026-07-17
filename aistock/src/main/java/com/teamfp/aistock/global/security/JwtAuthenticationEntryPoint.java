package com.teamfp.aistock.global.security;

// 주의: Spring Boot 4(Jackson 3.x)부터 ObjectMapper 패키지가 com.fasterxml.jackson.databind가 아니라
// tools.jackson.databind로 바뀌었다. com.fasterxml 쪽을 import하면 컴파일 자체가 안 되니 헷갈리지 않도록 주석으로 남겨둔다.
import tools.jackson.databind.ObjectMapper;
import com.teamfp.aistock.global.exception.ErrorCode;
import com.teamfp.aistock.global.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 인증되지 않은 사용자가 인증이 필요한 API에 접근했을 때 호출되는 진입점.
 *
 * Spring Security 기본 동작은 401 응답을 HTML 로그인 페이지나 빈 바디로 내려주는데,
 * CLAUDE.md 규칙상 모든 API 응답은 ApiResponse<T> 포맷(JSON)을 지켜야 하므로
 * SecurityConfig에서 이 클래스를 exceptionHandling().authenticationEntryPoint()에 등록해서
 * 401 응답도 다른 API 응답과 동일한 형태로 내려가도록 만든다.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    // JSON 직렬화용 - Spring Boot가 기본으로 등록해주는 ObjectMapper 빈을 그대로 주입받아 사용한다.
    private final ObjectMapper objectMapper;

    /**
     * 인증 실패(토큰 없음 / 유효하지 않은 토큰) 시 호출되는 메서드.
     * JwtAuthenticationFilter는 토큰이 유효하지 않으면 SecurityContext에 인증 정보를 채우지 않고 그냥 다음 필터로 넘기는데,
     * 이후 인증이 필요한 엔드포인트에 도달했을 때 Spring Security가 이 commence()를 호출해 401을 내려준다.
     */
    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException
    ) throws IOException {
        // 토큰 미존재/만료/위조 등 세부 사유를 구분하지 않고 공통적으로 INVALID_TOKEN(401)으로 응답한다.
        // (만료 여부까지 구분해서 안내하고 싶다면 JwtAuthenticationFilter에서 request attribute로 사유를 넘겨받아 분기해야 하는데,
        //  현재는 CLAUDE.md/ErrorCode에 정의된 범위 내에서 가장 단순한 방식으로 처리)
        ErrorCode errorCode = ErrorCode.INVALID_TOKEN;

        response.setStatus(errorCode.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        // ApiResponse.error(...)로 감싸서 success=false, message, data=null 형태의 공통 응답 포맷을 유지한다.
        response.getWriter().write(
                objectMapper.writeValueAsString(ApiResponse.error(errorCode.getMessage()))
        );
    }
}
