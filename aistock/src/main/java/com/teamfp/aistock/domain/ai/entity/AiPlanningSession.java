package com.teamfp.aistock.domain.ai.entity;

import java.time.LocalDateTime;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
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
@Table(name = "ai_planning_sessions", indexes = {
        @Index(name = "idx_user_session", columnList = "user_id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class AiPlanningSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "session_id")
    private Long sessionId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "title", length = 100)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private SessionStatus status;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    private AiPlanningSession(User user, String title) {
        this.user = user;
        this.title = title;
        this.status = SessionStatus.ACTIVE;
    }

    /**
     * 세션 제목 자동 채우기. 생성 시 title은 null이며, 첫 메시지가 저장될 때
     * AiPlanningService.sendMessage()가 사용자 메시지 앞부분을 잘라 한 번만 채운다.
     */
    public void updateTitle(String title) {
        this.title = title;
    }

    /**
     * 세션에 새 메시지가 오갈 때마다 호출해 updatedAt을 최신화한다. title은 첫 메시지에서
     * 한 번만 바뀌므로, title 변경이 없는 두 번째 메시지부터는 세션 엔티티 자체가 dirty로
     * 감지되지 않아 @LastModifiedDate가 자동으로 갱신되지 않는다 — 그 결과
     * getMySessions()의 "최근 대화순" 정렬이 방금 답장을 주고받은 세션에는 반영되지 않는
     * 문제가 있었다. 매 턴마다 명시적으로 호출해 정렬 기준을 항상 최신 상태로 유지한다.
     */
    public void recordActivity() {
        this.updatedAt = LocalDateTime.now();
    }
}
