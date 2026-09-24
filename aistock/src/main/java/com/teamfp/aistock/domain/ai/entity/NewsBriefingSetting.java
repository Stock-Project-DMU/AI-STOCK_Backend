package com.teamfp.aistock.domain.ai.entity;

import java.time.LocalDateTime;
import java.time.LocalTime;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.teamfp.aistock.domain.user.entity.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * feature/ai-news(맞춤형 뉴스 브리핑) — 사용자가 선택한 언론사 설정을 저장한다.
 * 한 사용자는 언론사를 한 번에 하나만 고를 수 있고(2026-08-24 사용자 확정), 언론사를 바꾸면
 * 다음 스케줄 실행부터 새 언론사 기사로 브리핑이 만들어진다. 이미 만들어진 과거 브리핑
 * ({@link NewsBriefing})은 생성 시점의 언론사를 그대로 유지하므로 이 설정과는 독립적이다.
 */
@Entity
@Table(name = "news_briefing_settings",
        uniqueConstraints = @UniqueConstraint(name = "uq_news_setting_user", columnNames = {"user_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class NewsBriefingSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "setting_id")
    private Long settingId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // NewsRelevanceMatcher.OUTLET_NAMES에 등록된 도메인 값(예: "hankyung.com")만 허용한다.
    // 서비스 계층(AiNewsService)이 저장 전 검증한다.
    @Column(name = "outlet_domain", length = 50, nullable = false)
    private String outletDomain;

    // 브리핑 생성 희망 시각(시:분:초, KST — schema.sql v15부터 분·초 단위까지 지원).
    // 기존 고정 새벽 7시와 동일한 기본값을 둬서 별도로 시각을 고르지 않은 사용자의
    // 체감 동작을 바꾸지 않는다.
    public static final LocalTime DEFAULT_BRIEFING_TIME = LocalTime.of(7, 0, 0);

    @Column(name = "briefing_time", nullable = false)
    private LocalTime briefingTime;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    private NewsBriefingSetting(User user, String outletDomain, LocalTime briefingTime) {
        this.user = user;
        this.outletDomain = outletDomain;
        this.briefingTime = briefingTime != null ? briefingTime : DEFAULT_BRIEFING_TIME;
    }

    // 언론사 변경(예: 한국경제 → 매일경제) — 상태 변경은 Setter 대신 의미 있는 메서드로.
    public void changeOutlet(String outletDomain) {
        this.outletDomain = outletDomain;
    }

    // 브리핑 생성 시각 변경(예: 07:00:00 → 22:15:30).
    public void changeBriefingTime(LocalTime briefingTime) {
        this.briefingTime = briefingTime;
    }
}
