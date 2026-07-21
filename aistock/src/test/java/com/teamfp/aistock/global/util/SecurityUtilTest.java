package com.teamfp.aistock.global.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.teamfp.aistock.domain.user.entity.Role;
import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;
import com.teamfp.aistock.global.security.CustomUserDetails;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * feature/order-market — SecurityUtil.getCurrentUserId() 단위 테스트.
 *
 * 정상 흐름에서는 SecurityConfig가 JWT 인증 없이는 컨트롤러까지 도달하지 못하게 막아주지만,
 * 이 유틸 메서드 자체는 그 전제에만 기대지 않고 Authentication이 비어있거나 principal이
 * CustomUserDetails가 아닌 경우(예: 익명 인증)를 직접 방어하는지 검증한다.
 */
class SecurityUtilTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("정상 인증 상태면 CustomUserDetails의 userId를 반환한다")
    void getCurrentUserId_success() {
        CustomUserDetails userDetails = new CustomUserDetails(1L, Role.USER);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities()));

        assertThat(SecurityUtil.getCurrentUserId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Authentication이 없으면(null) INVALID_TOKEN 예외를 던진다")
    void getCurrentUserId_fail_noAuthentication() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(SecurityUtil::getCurrentUserId)
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_TOKEN);
    }

    @Test
    @DisplayName("principal이 CustomUserDetails가 아니면(익명 인증 등) INVALID_TOKEN 예외를 던진다")
    void getCurrentUserId_fail_anonymousPrincipal() {
        SecurityContextHolder.getContext().setAuthentication(
                new AnonymousAuthenticationToken("key", "anonymousUser",
                        java.util.List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

        assertThatThrownBy(SecurityUtil::getCurrentUserId)
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_TOKEN);
    }
}
