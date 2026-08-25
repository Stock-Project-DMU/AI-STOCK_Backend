package com.teamfp.aistock.domain.ai.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.teamfp.aistock.domain.user.entity.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * feature/ai-news(맞춤형 뉴스 브리핑) — 스케줄러가 매일 생성한 "오늘의 브리핑" 결과.
 * 사용자별로 하루에 한 건만 존재한다(uq_news_briefing_user_date). outletDomain은
 * 브리핑 생성 시점의 {@link NewsBriefingSetting#getOutletDomain()}을 복사해 저장한다 —
 * 이후 사용자가 언론사를 바꿔도 과거 브리핑이 "그때는 어느 언론사 기준이었는지"를 그대로
 * 유지해야 하기 때문이다(설정과 결과를 분리한 이유).
 */
@Entity
@Table(name = "news_briefings",
        uniqueConstraints = @UniqueConstraint(name = "uq_news_briefing_user_date", columnNames = {"user_id", "briefing_date"}),
        indexes = @Index(name = "idx_news_briefing_user", columnList = "user_id, briefing_date"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class NewsBriefing {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "briefing_id")
    private Long briefingId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "outlet_domain", length = 50, nullable = false)
    private String outletDomain;

    @Column(name = "briefing_date", nullable = false)
    private LocalDate briefingDate;

    // Gemini가 생성한 요약 본문 — 길이 제한을 걱정할 필요 없는 자유 텍스트라 TEXT로 둔다.
    @Lob
    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    // 요약의 근거가 된 기사 목록(title/link/outlet) — Simulation.scenarioData와 동일한 패턴으로
    // JSON 컬럼에 문자열로 저장하고, 서비스 계층(AiNewsService)이 NewsSourceLinkDto 리스트로
    // 직렬화/역직렬화한다. 사용자가 "이 요약 진짜야?"를 직접 원문에서 확인하고, 원문 링크로도
    // 이동할 수 있게 하려는 목적(2026-08-24 사용자 요청).
    @Column(name = "source_links", nullable = false, columnDefinition = "json")
    private String sourceLinksJson;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    private NewsBriefing(User user, String outletDomain, LocalDate briefingDate, String content, String sourceLinksJson) {
        this.user = user;
        this.outletDomain = outletDomain;
        this.briefingDate = briefingDate;
        this.content = content;
        this.sourceLinksJson = sourceLinksJson;
    }
}
