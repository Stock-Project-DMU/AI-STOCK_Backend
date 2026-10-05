package com.teamfp.aistock.domain.auth.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.teamfp.aistock.domain.auth.dto.request.InitialAdminCreateRequest;
import com.teamfp.aistock.domain.auth.dto.response.InitialAdminStatusResponse;
import com.teamfp.aistock.domain.auth.dto.response.SignupResponse;
import com.teamfp.aistock.domain.auth.service.InitialAdminService;
import com.teamfp.aistock.global.response.ApiResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 최초 관리자 계정(feat/admin-improvements) — 로그인 없이 호출한다(SecurityConfig.PUBLIC_URLS).
 * - GET: 관리자가 있는지(adminExists=false면 프론트가 "최초 관리자 만들기" 모달을 띄운다)
 * - POST: 관리자가 0명이고 관리자 인증 코드가 맞을 때만 첫 관리자를 만든다
 */
@RestController
@RequestMapping("/api/auth/initial-admin")
@RequiredArgsConstructor
public class InitialAdminController {

    private final InitialAdminService initialAdminService;

    @GetMapping
    public ApiResponse<InitialAdminStatusResponse> getInitialAdminStatus() {
        return ApiResponse.success(initialAdminService.getStatus());
    }

    @PostMapping
    public ApiResponse<SignupResponse> createInitialAdmin(@Valid @RequestBody InitialAdminCreateRequest request) {
        return ApiResponse.success("최초 관리자 계정이 생성되었습니다.", initialAdminService.createInitialAdmin(request));
    }
}
