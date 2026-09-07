package com.teamfp.aistock.domain.user.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.teamfp.aistock.domain.user.dto.request.PasswordChangeRequest;
import com.teamfp.aistock.domain.user.dto.request.PasswordVerifyRequest;
import com.teamfp.aistock.domain.user.dto.request.SurveyRequest;
import com.teamfp.aistock.domain.user.dto.request.UpdateUserRequest;
import com.teamfp.aistock.domain.user.dto.response.InvestmentProfileResponse;
import com.teamfp.aistock.domain.user.dto.response.UserInfoResponse;
import com.teamfp.aistock.domain.user.service.UserService;
import com.teamfp.aistock.global.response.ApiResponse;
import com.teamfp.aistock.global.util.SecurityUtil;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/me")
    public ApiResponse<UserInfoResponse> getMyInfo() {
        Long userId = SecurityUtil.getCurrentUserId();
        return ApiResponse.success(userService.getMyInfo(userId));
    }

    @PatchMapping("/me")
    public ApiResponse<UserInfoResponse> updateMyInfo(@Valid @RequestBody UpdateUserRequest request) {
        Long userId = SecurityUtil.getCurrentUserId();
        return ApiResponse.success("회원 정보가 수정되었습니다.", userService.updateMyInfo(userId, request));
    }

    @PostMapping("/me/survey")
    public ApiResponse<InvestmentProfileResponse> saveSurvey(@Valid @RequestBody SurveyRequest request) {
        Long userId = SecurityUtil.getCurrentUserId();
        return ApiResponse.success("투자성향 설문이 저장되었습니다.", userService.saveSurvey(userId, request));
    }

    @PostMapping("/me/password/verify")
    public ApiResponse<Void> verifyPassword(@Valid @RequestBody PasswordVerifyRequest request) {
        Long userId = SecurityUtil.getCurrentUserId();
        userService.verifyPassword(userId, request);
        return ApiResponse.success("비밀번호가 확인되었습니다.", null);
    }

    @PatchMapping("/me/password")
    public ApiResponse<Void> changePassword(@Valid @RequestBody PasswordChangeRequest request) {
        Long userId = SecurityUtil.getCurrentUserId();
        userService.changePassword(userId, request);
        return ApiResponse.success("비밀번호가 변경되었습니다.", null);
    }
}
