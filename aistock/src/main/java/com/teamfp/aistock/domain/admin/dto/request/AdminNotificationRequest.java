package com.teamfp.aistock.domain.admin.dto.request;

import java.time.LocalDate;

import com.teamfp.aistock.domain.notification.entity.NotificationType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 관리자 알림 발송 요청 DTO(ADMIN_API_BACKEND_HANDOFF.md 4.4). 단일 유저 발송(POST
 * /api/admin/notifications/users/{userId})과 전체 발송(POST /api/admin/notifications/broadcast)이
 * 대상만 다르고 본문 형태는 동일해 요청 DTO를 하나로 공유한다.
 */
public record AdminNotificationRequest(
        @NotBlank
        @Size(max = 100, message = "알림 제목은 100자를 넘을 수 없습니다.")
        String title,
        @NotBlank
        @Size(max = 500, message = "알림 내용은 500자를 넘을 수 없습니다.")
        String content,
        @NotNull NotificationType type,
        // 공지 팝업(feat/admin-improvements) — true면 popupEndDate(포함)까지 받는 사람에게 팝업으로도 띄운다.
        Boolean popup,
        LocalDate popupEndDate
) {
}
