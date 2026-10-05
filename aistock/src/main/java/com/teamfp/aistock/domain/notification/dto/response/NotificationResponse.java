package com.teamfp.aistock.domain.notification.dto.response;

import java.time.LocalDateTime;

import com.teamfp.aistock.domain.notification.entity.Notification;
import com.teamfp.aistock.domain.notification.entity.NotificationType;

/**
 * 알림 한 건. noticeId는 관리자 공지로 받은 알림이면 그 공지 번호(feat/admin-improvements), 시스템 알림이면 null.
 */
public record NotificationResponse(
        Long notiId,
        NotificationType type,
        String title,
        String content,
        Long relatedOrderId,
        boolean isRead,
        LocalDateTime createdAt,
        Long noticeId
) {

    public static NotificationResponse from(Notification notification) {
        return new NotificationResponse(
                notification.getNotiId(),
                notification.getType(),
                notification.getTitle(),
                notification.getContent(),
                notification.getRelatedOrderId(),
                notification.isRead(),
                notification.getCreatedAt(),
                // 공지 프록시의 ID만 읽으므로 공지 자체를 추가로 조회하지 않는다.
                notification.getNotice() == null ? null : notification.getNotice().getNoticeId()
        );
    }
}
