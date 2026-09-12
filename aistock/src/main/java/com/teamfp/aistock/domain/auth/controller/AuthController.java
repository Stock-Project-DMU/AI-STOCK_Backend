package com.teamfp.aistock.domain.auth.controller;

import com.teamfp.aistock.domain.auth.dto.request.EmailCodeRequest;
import com.teamfp.aistock.domain.auth.dto.request.EmailCodeVerifyRequest;
import com.teamfp.aistock.domain.auth.dto.request.LoginRequest;
import com.teamfp.aistock.domain.auth.dto.request.OAuthLoginRequest;
import com.teamfp.aistock.domain.auth.dto.request.SignupRequest;
import com.teamfp.aistock.domain.auth.dto.response.LoginResponse;
import com.teamfp.aistock.domain.auth.dto.response.SignupResponse;
import com.teamfp.aistock.domain.auth.dto.response.TokenResponse;
import com.teamfp.aistock.domain.auth.service.AuthService;
import com.teamfp.aistock.global.response.ApiResponse;
import com.teamfp.aistock.global.security.JwtProvider;
import com.teamfp.aistock.global.util.SecurityUtil;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    @GetMapping("/login-id/availability")
    public ApiResponse<com.teamfp.aistock.domain.auth.dto.response.LoginIdCheckResponse> checkLoginId(@RequestParam String loginId) {
        return ApiResponse.success("아이디 중복 확인 결과입니다.", authService.checkLoginId(loginId));
    }

    @PostMapping("/find-id")
    public ApiResponse<String> findLoginId(@Valid @RequestBody com.teamfp.aistock.domain.auth.dto.request.AccountRecoveryRequest request) {
        return ApiResponse.success("아이디를 확인했습니다.", authService.findLoginId(request));
    }

    @PostMapping("/password/reset")
    public ApiResponse<Void> resetPassword(@Valid @RequestBody com.teamfp.aistock.domain.auth.dto.request.AccountRecoveryRequest request) {
        authService.resetPassword(request);
        return ApiResponse.success("비밀번호가 변경되었습니다.", null);
    }

    private final AuthService authService;
    private final JwtProvider jwtProvider;

    /**
     * 일반 로그인 API
     */
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        LoginResponse response = authService.login(request);
        return ApiResponse.success("로그인에 성공했습니다.", response);
    }

    /**
     * 소셜 로그인 API (카카오, 네이버, 구글 통합)
     */
    @PostMapping("/oauth/login")
    public ApiResponse<LoginResponse> socialLogin(@Valid @RequestBody OAuthLoginRequest request, jakarta.servlet.http.HttpServletRequest servletRequest) {
        OAuthAuthorizationController.consumeState(servletRequest.getSession(false), request.getProvider(), request.getState());
        LoginResponse response = authService.socialLogin(request);
        return ApiResponse.success("소셜 로그인에 성공했습니다.", response);
    }

    /**
     * 토큰 재발급 API (Refresh Token 활용)
     */
    @PostMapping("/refresh")
    public ApiResponse<TokenResponse> refresh(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        TokenResponse response = authService.refresh(authHeader);
        return ApiResponse.success("토큰 재발급에 성공했습니다.", response);
    }

    /**
     * 로그아웃 API. Access Token 블랙리스트 등록 + Refresh Token 삭제.
     */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        Long userId = SecurityUtil.getCurrentUserId();
        String accessToken = jwtProvider.extractBearerToken(authHeader);
        authService.logout(userId, accessToken);
        return ApiResponse.success("로그아웃되었습니다.", null);
    }

    /**
     * 회원가입 API
     */
    @PostMapping("/signup")
    public ApiResponse<SignupResponse> signup(@Valid @RequestBody SignupRequest request) {
        SignupResponse response = authService.signup(request);
        return ApiResponse.success("회원가입에 성공했습니다.", response);
    }

    /**
     * 이메일 인증코드 발송 API
     */
    @PostMapping("/email/send-code")
    public ApiResponse<Void> sendEmailCode(@Valid @RequestBody EmailCodeRequest request) {
        authService.sendEmailCode(request.getEmail());
        return ApiResponse.success("인증코드가 발송되었습니다.", null);
    }

    /**
     * 이메일 인증코드 검증 API
     */
    @PostMapping("/email/verify-code")
    public ApiResponse<Void> verifyEmailCode(@Valid @RequestBody EmailCodeVerifyRequest request) {
        authService.verifyEmailCode(request.getEmail(), request.getCode());
        return ApiResponse.success("이메일 인증에 성공했습니다.", null);
    }
}
