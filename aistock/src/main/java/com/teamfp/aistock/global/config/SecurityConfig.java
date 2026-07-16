package com.teamfp.aistock.global.config;

import com.teamfp.aistock.global.security.CustomUserDetailsService;
import com.teamfp.aistock.global.security.JwtAccessDeniedHandler;
import com.teamfp.aistock.global.security.JwtAuthenticationEntryPoint;
import com.teamfp.aistock.global.security.JwtAuthenticationFilter;
import com.teamfp.aistock.global.security.JwtProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Spring Security 전역 설정.
 *
 * 이 프로젝트는 세션 기반이 아니라 JWT(Access/Refresh Token) 기반 인증을 사용하므로
 * - 서버가 세션을 만들지 않도록 STATELESS로 설정하고,
 * - 폼 로그인/기본 HTTP Basic 인증 같은 Spring Security 기본 로그인 방식은 전부 끄고,
 * - 대신 매 요청마다 JwtAuthenticationFilter가 헤더의 Authorization: Bearer {token}을 검사해서
 *   SecurityContext에 인증 정보를 채워 넣는 방식으로 동작한다.
 *
 * "로그인 없이 되는 페이지 / 안 되는 페이지" 구분(permitAll 목록)과
 * "프론트 도메인 허용"(CORS)이 이 클래스의 핵심 역할이다.
 */
@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    // JwtAuthenticationFilter 생성에 필요한 의존성들 (필터 자체는 @Component가 아니라 여기서 직접 생성해서 등록한다)
    private final JwtProvider jwtProvider;
    private final CustomUserDetailsService customUserDetailsService;

    // 인증 실패(401) / 인가 실패(403) 시 ApiResponse 포맷 JSON으로 응답을 내려주는 커스텀 핸들러
    private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;
    private final JwtAccessDeniedHandler jwtAccessDeniedHandler;

    // application.yml의 cors.allowed-origins 값을 주입받는다. 콤마(,)로 여러 도메인을 구분해서 넣을 수 있다.
    @Value("${cors.allowed-origins}")
    private String allowedOrigins;

    /**
     * 인증 없이 접근 가능한(permitAll) API 경로 목록.
     *
     * NAMING.md 8-1/8-2 기준으로 "로그인 전"에 호출되어야 하는 인증 관련 API만 공개하고,
     * 그 외 모든 API(마이페이지, 시세, 주문, AI 등)는 로그인(JWT)이 필요하다.
     * - 로그아웃(/api/auth/logout)은 이미 로그인된 사용자가 자기 토큰을 무효화하는 동작이라 인증이 필요하므로 목록에서 제외한다.
     * - WebSocket 핸드셰이크(/ws-stomp/**)는 HTTP 레벨에서는 열어두고,
     *   실제 인증은 CLAUDE.md 8번 항목에 따라 별도의 StompAuthInterceptor(feature/websocket-config)가
     *   STOMP CONNECT 프레임 단계에서 처리한다.
     */
    private static final String[] PUBLIC_URLS = {
            "/api/auth/login",
            "/api/auth/signup",
            "/api/auth/oauth/**",
            "/api/auth/refresh",
            "/api/auth/email/**",
            "/ws-stomp/**"
    };

    /**
     * 비밀번호 암호화에 사용할 인코더.
     * 회원가입/로그인 시 평문 비밀번호를 그대로 저장/비교하지 않기 위해
     * AuthService에서 이 빈을 주입받아 encode()/matches()로 사용한다.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * CORS(Cross-Origin Resource Sharing) 설정.
     * 프론트엔드가 백엔드와 다른 도메인/포트에서 API를 호출할 수 있도록 허용 목록을 등록한다.
     * 여기 등록되지 않은 Origin에서의 요청은 브라우저가 자체적으로 차단한다.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        // application.yml의 cors.allowed-origins 값을 콤마 기준으로 나눠서 등록한다.
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .toList();
        configuration.setAllowedOrigins(origins);

        // REST API에서 쓰는 HTTP 메서드 전체 허용
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));

        // Authorization(JWT), Content-Type 등 프론트에서 보내는 모든 요청 헤더 허용
        configuration.setAllowedHeaders(List.of("*"));

        // Access/Refresh Token을 쿠키가 아니라 Authorization 헤더로 주고받더라도,
        // 향후 쿠키 기반 Refresh Token 등으로 확장될 가능성을 감안해 자격 증명 포함 요청을 허용한다.
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    /**
     * 실제 보안 필터 체인 구성.
     * 요청이 컨트롤러에 도달하기 전에 이 체인을 거치면서 CORS/인증/인가가 처리된다.
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // 위에서 정의한 CORS 정책 적용
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))

                // JWT를 헤더로 주고받는 stateless API이므로 CSRF 토큰이 필요 없다 (세션/쿠키 기반이 아님).
                .csrf(AbstractHttpConfigurer::disable)

                // 세션을 아예 생성하지 않는다 - 모든 인증 상태는 매 요청마다 JWT로부터 새로 판단한다.
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // 폼 로그인 화면, HTTP Basic 인증 팝업 등 이 프로젝트에서 쓰지 않는 기본 인증 방식을 모두 비활성화
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)

                // 경로별 인가 규칙: PUBLIC_URLS는 누구나 접근 가능, 그 외에는 인증(JWT) 필요
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_URLS).permitAll()
                        .anyRequest().authenticated()
                )

                // 인증 실패(401)/인가 실패(403) 시 기본 HTML 응답 대신 ApiResponse JSON으로 응답하도록 커스텀 핸들러 연결
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(jwtAuthenticationEntryPoint)
                        .accessDeniedHandler(jwtAccessDeniedHandler)
                )

                // Spring Security가 기본으로 등록하는 UsernamePasswordAuthenticationFilter(폼 로그인용) 대신,
                // 그 앞단에 JwtAuthenticationFilter를 끼워 넣어 매 요청마다 JWT를 검사하도록 한다.
                .addFilterBefore(
                        new JwtAuthenticationFilter(jwtProvider, customUserDetailsService),
                        UsernamePasswordAuthenticationFilter.class
                );

        return http.build();
    }
}
