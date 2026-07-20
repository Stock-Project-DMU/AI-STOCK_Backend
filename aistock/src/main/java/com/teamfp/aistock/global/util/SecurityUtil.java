package com.teamfp.aistock.global.util;

import org.springframework.security.core.context.SecurityContextHolder;

import com.teamfp.aistock.global.security.CustomUserDetails;

/**
 * 현재 요청을 보낸 로그인 사용자 정보를 SecurityContext에서 꺼내오는 유틸.
 *
 * JwtAuthenticationFilter가 JWT 검증에 성공하면 SecurityContextHolder에
 * CustomUserDetails(principal)를 담은 Authentication을 채워 넣는다. 이 클래스는
 * Controller/Service에서 그 값을 매번 캐스팅해서 꺼내지 않도록 감싸주는 정적 헬퍼다.
 * 인스턴스를 만들 이유가 없는 유틸 클래스이므로 생성자를 막아둔다.
 */
public class SecurityUtil {

    private SecurityUtil() {
    }

    /**
     * 현재 인증된 사용자의 userId를 반환한다.
     * SecurityConfig에서 인증이 필요 없는 PUBLIC_URLS 외 모든 요청은 JwtAuthenticationFilter를
     * 통과해야만 컨트롤러까지 도달하므로, 이 메서드가 호출되는 시점에는 항상 인증 정보가
     * 채워져 있다는 전제를 둔다.
     */
    public static Long getCurrentUserId() {
        CustomUserDetails userDetails =
                (CustomUserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return userDetails.getUserId();
    }
}
