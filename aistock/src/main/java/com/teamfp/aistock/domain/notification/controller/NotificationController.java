package com.teamfp.aistock.domain.notification.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.teamfp.aistock.domain.notification.dto.response.NoticePopupResponse;
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

    // 공지 팝업(feat/admin-improvements) — 로그인 직후 띄울 내 팝업 목록(팝업 기한이 오늘 이후인 받은 공지).
    // 닫기는 화면에서만 처리하고 서버에 기록하지 않아, 기한까지는 로그인할 때마다 다시 뜬다.
    @GetMapping("/popups")
    public ApiResponse<List<NoticePopupResponse>> getActivePopups() {
        return ApiResponse.success(notificationService.getActivePopups(SecurityUtil.getCurrentUserId()));
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
