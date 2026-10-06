package com.teamfp.aistock.domain.notification.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 관리자가 보낸 공지 한 건(feat/admin-improvements, 2026-10-05 사용자 승인으로 신규 테이블). 공지를 보내면 받는 회원마다
 * 알림(notifications)이 한 건씩 생기고 그 알림들이 notice_id로 이 공지를 가리킨다 — 관리자 "알림 관리"에서 보낸 공지를
 * 한 건 단위로 보고, 팝업 기한을 바꾸거나 삭제(받은 회원 알림함에서도 함께 삭제)한다.
 *
 * - popupEndDate: 이 날짜(포함)까지 받는 회원이 로그인할 때마다 팝업으로 띄운다. null이면 팝업이 아닌 일반 공지다.
 *   "지금 바로 종료"는 어제 날짜로 바꿔 기록을 남긴다(팝업이었던 공지인지는 계속 알 수 있다).
 * - createdBy/createdByLoginId: 보낸 관리자. 아이디는 보낸 시점 값을 스냅샷으로 남긴다(AuditLog와 같은 이유).
 */
@Entity
@Table(name = "notices", indexes = {
        @Index(name = "idx_notice_created_at", columnList = "created_at")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class Notice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "notice_id")
    private Long noticeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 15)
    private NotificationType type;

    @Column(name = "title", length = 100, nullable = false)
    private String title;

    @Column(name = "content", length = 500, nullable = false)
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 10)
    private NoticeTargetType targetType;

    @Column(name = "target_count", nullable = false)
    private int targetCount;

    @Column(name = "sent_count", nullable = false)
    private int sentCount;

    @Column(name = "popup_end_date")
    private LocalDate popupEndDate;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_by_login_id", length = 50)
    private String createdByLoginId;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    private Notice(NotificationType type, String title, String content, NoticeTargetType targetType,
            LocalDate popupEndDate, Long createdBy, String createdByLoginId) {
        this.type = type;
        this.title = title;
        this.content = content;
        this.targetType = targetType;
        this.popupEndDate = popupEndDate;
        this.createdBy = createdBy;
        this.createdByLoginId = createdByLoginId;
    }

    // 발송이 끝난 뒤 대상 수와 실제 저장된 수를 기록한다.
    public void recordSendResult(int targetCount, int sentCount) {
        this.targetCount = targetCount;
        this.sentCount = sentCount;
    }

    // 팝업 기한 변경 — 일반 공지였다면 이때부터 팝업이 된다.
    public void changePopupEndDate(LocalDate popupEndDate) {
        this.popupEndDate = popupEndDate;
    }

    // 지금 바로 팝업 종료 — 어제 날짜로 바꿔 오늘부터 안 뜨게 한다.
    public void endPopup(LocalDate today) {
        this.popupEndDate = today.minusDays(1);
    }

    public boolean isPopup() {
        return popupEndDate != null;
    }

    public boolean isPopupActive(LocalDate today) {
        return popupEndDate != null && !popupEndDate.isBefore(today);
    }
}
