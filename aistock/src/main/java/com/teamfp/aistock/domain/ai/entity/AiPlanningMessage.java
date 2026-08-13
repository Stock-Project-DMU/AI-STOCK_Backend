package com.teamfp.aistock.domain.ai.entity;

import java.time.LocalDateTime;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

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
@Table(name = "ai_planning_messages", indexes = {
        @Index(name = "idx_session_msg", columnList = "session_id, created_at")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class AiPlanningMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "message_id")
    private Long messageId;

    // schema.sql: ai_planning_messages.session_id FK ... ON DELETE CASCADE. ddl-auto=update는
    // JPA 표준 애노테이션만으로는 이 DB 레벨 CASCADE를 만들어주지 않아(@OnDelete 없이는 그냥
    // FK만 생성됨) 실제 부팅 테스트에서 확인 후 명시적으로 추가했다. AiPlanningSessionRepository.
    // deleteByUserId()가 세션을 벌크(JPQL) 삭제하는데, 이 DB 레벨 CASCADE가 없으면 세션에
    // 딸린 메시지가 남아있어 FK 제약 위반으로 삭제 자체가 실패한다.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private AiPlanningSession session;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 10)
    private MessageRole role;

    // schema.sql: content TEXT. @Lob만 쓰면(길이 힌트가 없어) 이 프로젝트의 Hibernate/MySQL8
    // 조합에서 TINYTEXT(255바이트)로 생성되어 실제 부팅 테스트에서 "Data too long" 에러로
    // 확인됐다 — columnDefinition으로 스키마와 동일한 TEXT를 명시해 강제한다.
    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    private AiPlanningMessage(AiPlanningSession session, MessageRole role, String content, Integer promptTokens) {
        this.session = session;
        this.role = role;
        this.content = content;
        this.promptTokens = promptTokens;
    }
}
