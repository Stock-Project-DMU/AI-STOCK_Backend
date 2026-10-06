package com.teamfp.aistock.domain.notification.dto.response;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.teamfp.aistock.domain.notification.entity.Notice;
import com.teamfp.aistock.domain.notification.entity.Notification;
import com.teamfp.aistock.domain.notification.entity.NotificationType;

/**
 * 로그인할 때 띄울 공지 팝업 한 건(feat/admin-improvements, GET /api/notifications/popups). 제목·내용·기한은 공지 기준이고,
 * notiId는 이 회원 알림함의 그 알림(읽음 처리 등에 쓴다).
 */
public record NoticePopupResponse(
        Long noticeId,
        Long notiId,
        NotificationType type,
        String title,
        String content,
        LocalDate popupEndDate,
        LocalDateTime createdAt
) {

    public static NoticePopupResponse from(Notification notification) {
        Notice notice = notification.getNotice();
        return new NoticePopupResponse(
                notice.getNoticeId(),
                notification.getNotiId(),
                notice.getType(),
                notice.getTitle(),
                notice.getContent(),
                notice.getPopupEndDate(),
                notice.getCreatedAt()
        );
    }
}
