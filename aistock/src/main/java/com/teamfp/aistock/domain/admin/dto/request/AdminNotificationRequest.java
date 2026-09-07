package com.teamfp.aistock.domain.admin.dto.request;

import com.teamfp.aistock.domain.notification.entity.NotificationType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 관리자 알림 발송 요청 DTO(ADMIN_API_BACKEND_HANDOFF.md 4.4). 단일 유저 발송(POST
 * /api/admin/notifications/users/{userId})과 전체 발송(POST /api/admin/notifications/broadcast)이
 * 대상만 다르고 본문 형태는 동일해 요청 DTO를 하나로 공유한다.
 */
public record AdminNotificationRequest(
        @NotBlank String title,
        @NotBlank String content,
        @NotNull NotificationType type
) {
}
