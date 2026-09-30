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
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "news_chat_sessions",
        uniqueConstraints = @UniqueConstraint(name = "uq_news_chat_user_setting", columnNames = {"user_id", "setting_key"}),
        indexes = @Index(name = "idx_news_chat_user_updated", columnList = "user_id, updated_at"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class NewsChatSession {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "session_id")
    private Long sessionId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "setting_key", nullable = false, length = 80)
    private String settingKey;

    @Column(name = "outlet_domain", length = 50)
    private String outletDomain;

    @Column(name = "delivery_time")
    private LocalTime deliveryTime;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    private NewsChatSession(User user, String settingKey, String outletDomain, LocalTime deliveryTime) {
        this.user = user;
        this.settingKey = settingKey;
        this.outletDomain = outletDomain;
        this.deliveryTime = deliveryTime;
    }

    public void recordActivity() {
        this.updatedAt = LocalDateTime.now();
    }
}
