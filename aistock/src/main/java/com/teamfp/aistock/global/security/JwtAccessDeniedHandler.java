package com.teamfp.aistock.global.security;

// 주의: Spring Boot 4(Jackson 3.x)부터 ObjectMapper 패키지가 com.fasterxml.jackson.databind가 아니라
// tools.jackson.databind로 바뀌었다.
import tools.jackson.databind.ObjectMapper;
import com.teamfp.aistock.global.exception.ErrorCode;
import com.teamfp.aistock.global.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 로그인은 했지만(=토큰은 유효하지만) 해당 리소스에 대한 권한이 없을 때 호출되는 핸들러.
 * 예) 일반 USER 권한으로 ADMIN 전용 API에 접근한 경우.
 *
 * JwtAuthenticationEntryPoint(401)와 마찬가지로, Spring Security 기본 403 응답 대신
 * ApiResponse 포맷(JSON)으로 통일해서 내려주기 위해 별도로 구현한다.
 */
@Component
@RequiredArgsConstructor
public class JwtAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException
    ) throws IOException {
        ErrorCode errorCode = ErrorCode.ACCESS_DENIED;

        response.setStatus(errorCode.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(
                objectMapper.writeValueAsString(ApiResponse.error(errorCode.getMessage()))
        );
    }
}
