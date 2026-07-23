package com.teamfp.aistock.domain.auth.controller;

import com.teamfp.aistock.domain.auth.dto.request.LoginRequest;
import com.teamfp.aistock.domain.auth.dto.request.OAuthLoginRequest;
import com.teamfp.aistock.domain.auth.dto.response.LoginResponse;
import com.teamfp.aistock.domain.auth.dto.response.TokenResponse;
import com.teamfp.aistock.domain.auth.service.AuthService;
import com.teamfp.aistock.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

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
    public ApiResponse<LoginResponse> socialLogin(@Valid @RequestBody OAuthLoginRequest request) {
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
}
