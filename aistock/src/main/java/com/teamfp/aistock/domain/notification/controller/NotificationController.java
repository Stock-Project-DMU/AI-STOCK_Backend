package com.teamfp.aistock.domain.notification.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.teamfp.aistock.domain.notification.dto.response.NotificationCountResponse;
import com.teamfp.aistock.domain.notification.dto.response.NotificationResponse;
import com.teamfp.aistock.domain.notification.service.NotificationService;
import com.teamfp.aistock.global.response.ApiResponse;
import com.teamfp.aistock.global.util.SecurityUtil;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    /**
     * 내 알림 목록 조회 API
     */
    @GetMapping
    public ApiResponse<List<NotificationResponse>> getMyNotifications() {
        Long userId = SecurityUtil.getCurrentUserId();
        return ApiResponse.success(notificationService.getMyNotifications(userId));
    }

    /**
     * 안 읽은 알림 개수 조회 API
     */
    @GetMapping("/unread-count")
    public ApiResponse<NotificationCountResponse> getUnreadCount() {
        Long userId = SecurityUtil.getCurrentUserId();
        return ApiResponse.success(notificationService.getUnreadCount(userId));
    }

    /**
     * 알림 읽음 처리 API
     */
    @PatchMapping("/{notiId}/read")
    public ApiResponse<Void> markAsRead(@PathVariable Long notiId) {
        Long userId = SecurityUtil.getCurrentUserId();
        notificationService.markAsRead(userId, notiId);
        return ApiResponse.success("읽음 처리되었습니다.", null);
    }
}
