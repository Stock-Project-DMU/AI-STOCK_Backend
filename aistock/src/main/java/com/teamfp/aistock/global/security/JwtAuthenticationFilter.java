package com.teamfp.aistock.global.security;

import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.redis.RedisTokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Slf4j
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtProvider jwtProvider;
    private final CustomUserDetailsService customUserDetailsService;
    private final RedisTokenService redisTokenService;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String token = jwtProvider.resolveToken(request);

        // 로그아웃 시 블랙리스트에 등록된 토큰은 만료 전이라도 인증에서 제외한다.
        if (StringUtils.hasText(token) && jwtProvider.validateToken(token) && !redisTokenService.isBlacklisted(token)) {
            authenticate(token, request);
        }

        filterChain.doFilter(request, response);
    }

    private void authenticate(String token, HttpServletRequest request) {
        try {
            Long userId = jwtProvider.getUserId(token);
            CustomUserDetails userDetails = customUserDetailsService.loadUserByUsername(String.valueOf(userId));

            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
            // 요청 IP를 Authentication.details에 담아둔다(코드리뷰 반영, 2026-09) — 감사 로그
            // (AuditLogService.record())가 어느 서비스 메서드 시그니처도 바꾸지 않고
            // SecurityContextHolder를 통해 요청 IP를 꺼낼 수 있게 하기 위함이다.
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (CustomException e) {
            log.warn("JWT 인증 실패: {}", e.getMessage());
        }
    }
}
