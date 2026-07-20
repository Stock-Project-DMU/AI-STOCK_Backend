package com.teamfp.aistock.global.util;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;
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
     * 통과해야만 컨트롤러까지 도달하므로, 정상 흐름에서는 항상 인증 정보가 채워져 있다.
     * 다만 필터 체인 설정 변경이나 익명 인증(principal이 CustomUserDetails가 아닌 경우) 같은
     * 예외적인 상황에서 무검증 캐스팅이 NullPointerException/ClassCastException으로 이어져
     * 의미 없는 500을 내지 않도록, 여기서 명시적으로 검사해 CustomException으로 변환한다.
     */
    public static Long getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof CustomUserDetails userDetails)) {
            throw new CustomException(ErrorCode.INVALID_TOKEN);
        }
        return userDetails.getUserId();
    }
}
