package com.teamfp.aistock.domain.admin.dto.response;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.teamfp.aistock.domain.notification.entity.Notice;
import com.teamfp.aistock.domain.notification.entity.NoticeTargetType;
import com.teamfp.aistock.domain.notification.entity.NotificationType;

/**
 * 관리자 알림 관리 — 보낸 공지 한 건(feat/admin-improvements). 목록과 상세가 같이 쓴다.
 * - targetType: SINGLE(회원 한 명) / ALL(전체 회원) / SEARCH(검색 결과 전체, 일부 제외) / SELECTED(고른 회원)
 * - targetCount/sentCount: 보낸 대상 수와 실제 저장된 수
 * - popup: 팝업 공지였는지(popupEndDate가 있으면 true), popupActive: 오늘 기준 아직 팝업이 뜨는지
 * - recipientCount/readCount: 지금 알림함에 남아 있는 받은 회원 수와 그중 읽은 수 — 상세에서만 채우고 목록은 null
 *   (탈퇴한 회원의 알림은 지워져 recipientCount가 sentCount보다 작을 수 있다)
 */
public record AdminNoticeResponse(
        Long noticeId,
        NotificationType type,
        String title,
        String content,
        NoticeTargetType targetType,
        int targetCount,
        int sentCount,
        boolean popup,
        LocalDate popupEndDate,
        boolean popupActive,
        Long createdBy,
        String createdByLoginId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        Long recipientCount,
        Long readCount
) {

    public static AdminNoticeResponse from(Notice notice, LocalDate today) {
        return of(notice, today, null, null);
    }

    public static AdminNoticeResponse of(Notice notice, LocalDate today, Long recipientCount, Long readCount) {
        return new AdminNoticeResponse(
                notice.getNoticeId(),
                notice.getType(),
                notice.getTitle(),
                notice.getContent(),
                notice.getTargetType(),
                notice.getTargetCount(),
                notice.getSentCount(),
                notice.isPopup(),
                notice.getPopupEndDate(),
                notice.isPopupActive(today),
                notice.getCreatedBy(),
                notice.getCreatedByLoginId(),
                notice.getCreatedAt(),
                notice.getUpdatedAt(),
                recipientCount,
                readCount
        );
    }
}
