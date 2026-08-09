package com.teamfp.aistock.domain.notification.dto.response;

public record NotificationCountResponse(long unreadCount) {

    public static NotificationCountResponse from(long unreadCount) {
        return new NotificationCountResponse(unreadCount);
    }
}
