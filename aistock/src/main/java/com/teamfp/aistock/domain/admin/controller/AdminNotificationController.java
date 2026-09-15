package com.teamfp.aistock.domain.admin.controller;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.teamfp.aistock.domain.admin.dto.request.AdminNotificationRequest;
import com.teamfp.aistock.domain.admin.service.AdminNotificationService;
import com.teamfp.aistock.global.response.ApiResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 관리자 — 사용자 알림 발송 API. SecurityConfig에서 "/api/admin/**"는 hasRole("ADMIN")로
 * 이미 제한되어 있어 여기서는 별도의 권한 검사를 하지 않는다.
 */
@RestController
@RequestMapping("/api/admin/notifications")
@RequiredArgsConstructor
public class AdminNotificationController {

    private final AdminNotificationService adminNotificationService;

    @PostMapping("/users/{userId}")
    public ApiResponse<Void> notifyUser(@PathVariable Long userId, @Valid @RequestBody AdminNotificationRequest request) {
        adminNotificationService.notifyUser(userId, request);
        return ApiResponse.success("알림을 발송했습니다.", null);
    }

    @PostMapping("/broadcast")
    public ApiResponse<Void> broadcast(@Valid @RequestBody AdminNotificationRequest request) {
        adminNotificationService.broadcast(request);
        return ApiResponse.success("전체 알림을 발송했습니다.", null);
    }
}
