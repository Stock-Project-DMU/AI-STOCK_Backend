package com.teamfp.aistock.domain.notification.dto.response;

import java.time.LocalDateTime;

import com.teamfp.aistock.domain.notification.entity.Notification;
import com.teamfp.aistock.domain.notification.entity.NotificationType;

public record NotificationResponse(
        Long notiId,
        NotificationType type,
        String title,
        String content,
        boolean isRead,
        LocalDateTime createdAt
) {

    public static NotificationResponse from(Notification notification) {
        return new NotificationResponse(
                notification.getNotiId(),
                notification.getType(),
                notification.getTitle(),
                notification.getContent(),
                notification.isRead(),
                notification.getCreatedAt()
        );
    }
}
