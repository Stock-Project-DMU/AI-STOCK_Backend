package com.teamfp.aistock.domain.admin.controller;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.teamfp.aistock.domain.admin.dto.request.AdminNotificationRequest;
import com.teamfp.aistock.domain.admin.dto.request.AdminNotificationSendRequest;
import com.teamfp.aistock.domain.admin.dto.response.AdminNotificationSendResponse;
import com.teamfp.aistock.domain.admin.service.AdminNotificationService;
import com.teamfp.aistock.global.response.ApiResponse;
import com.teamfp.aistock.global.util.SecurityUtil;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 관리자 — 사용자 알림 발송 API. SecurityConfig에서 "/api/admin/**"는 hasRole("ADMIN")로
 * 이미 제한되어 있어 여기서는 별도의 권한 검사를 하지 않는다. feat/admin-improvements부터 세 발송 API 모두
 * 응답 data에 발송 대상 수·성공 수(AdminNotificationSendResponse)를 담는다.
 */
@RestController
@RequestMapping("/api/admin/notifications")
@RequiredArgsConstructor
public class AdminNotificationController {

    private final AdminNotificationService adminNotificationService;

    @PostMapping("/users/{userId}")
    public ApiResponse<AdminNotificationSendResponse> notifyUser(@PathVariable Long userId,
            @Valid @RequestBody AdminNotificationRequest request) {
        return ApiResponse.success("알림을 발송했습니다.", adminNotificationService.notifyUser(SecurityUtil.getCurrentUserId(), userId, request));
    }

    @PostMapping("/broadcast")
    public ApiResponse<AdminNotificationSendResponse> broadcast(@Valid @RequestBody AdminNotificationRequest request) {
        return ApiResponse.success("전체 알림을 발송했습니다.", adminNotificationService.broadcast(SecurityUtil.getCurrentUserId(), request));
    }

    // 공지 선택 발송 — 회원 목록에서 "전체 선택"(검색 결과 전체, 일부 제외 가능) 또는 개별·현 페이지 선택으로 보낸다.
    @PostMapping("/send")
    public ApiResponse<AdminNotificationSendResponse> send(@Valid @RequestBody AdminNotificationSendRequest request) {
        AdminNotificationSendResponse result = adminNotificationService.send(SecurityUtil.getCurrentUserId(), request);
        return ApiResponse.success(String.format("%d명에게 알림을 발송했습니다.", result.sentCount()), result);
    }
}
