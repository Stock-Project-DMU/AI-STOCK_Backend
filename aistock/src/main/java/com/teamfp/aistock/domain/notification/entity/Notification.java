package com.teamfp.aistock.domain.notification.entity;

import java.time.LocalDateTime;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.teamfp.aistock.domain.user.entity.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "notifications", indexes = {
        @Index(name = "idx_user_noti", columnList = "user_id, is_read"),
        // 공지 단위 조회·삭제(관리자 알림 관리, feat/admin-improvements)
        @Index(name = "idx_noti_notice", columnList = "notice_id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "noti_id")
    private Long notiId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 15)
    private NotificationType type;

    @Column(name = "title", length = 100, nullable = false)
    private String title;

    @Column(name = "content", length = 500, nullable = false)
    private String content;

    @Column(name = "related_order_id")
    private Long relatedOrderId;

    @Column(name = "is_read", nullable = false)
    private boolean isRead;

    // 관리자 공지로 받은 알림이면 그 공지(feat/admin-improvements). 팝업 여부·기한은 공지(Notice.popupEndDate)를 따른다.
    // 주문 체결·충전 처리 같은 시스템 알림은 null이다.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "notice_id")
    private Notice notice;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    private Notification(User user, NotificationType type, String title, String content, Long relatedOrderId,
            Notice notice) {
        this.user = user;
        this.type = type;
        this.title = title;
        this.content = content;
        this.relatedOrderId = relatedOrderId;
        this.notice = notice;
        this.isRead = false;
    }

    public void markAsRead() {
        this.isRead = true;
    }
}
