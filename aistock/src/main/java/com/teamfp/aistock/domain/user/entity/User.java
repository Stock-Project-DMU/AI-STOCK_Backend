package com.teamfp.aistock.domain.user.entity;

import org.hibernate.annotations.ColumnDefault;
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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "users", indexes = {
        @Index(name = "idx_email", columnList = "email"),
        @Index(name = "idx_active", columnList = "is_active"),
        @Index(name = "idx_status", columnList = "status")
})
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@EntityListeners(AuditingEntityListener.class)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "login_id", length = 50, unique = true)
    private String loginId;

    @Column(name = "password", length = 255)
    private String password;

    @Column(name = "name", length = 50, nullable = false)
    private String name;

    @Column(name = "birthdate")
    private LocalDate birthdate;

    @Column(name = "email", length = 100, unique = true)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    @Builder.Default
    private Role role = Role.USER;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    @ColumnDefault("'ACTIVE'")
    @Builder.Default
    private UserStatus status = UserStatus.ACTIVE;

    @Column(name = "is_active", nullable = false)
    private boolean isActive;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * 탈퇴 처리 — schema.sql "탈퇴 처리 전략" 3단계(PII 익명화)에 해당하는 부분만 수행한다.
     * social_accounts/investment_profile/accounts 등 자식 테이블 삭제와 Redis 정리(1·2단계)는
     * 여러 도메인의 Repository를 조합해야 하는 탈퇴 서비스 로직(예: feature/user-withdrawal)에서
     * 이 메서드 호출 "이후"에 순서대로 처리해야 한다 — 이 메서드 하나로 탈퇴가 완결되지 않는다.
     */
    public void deactivate() {
        this.loginId = "deleted_" + this.userId;
        this.password = null;
        this.name = "탈퇴회원";
        this.birthdate = null;
        this.email = null;
        this.isActive = false;
        this.deletedAt = LocalDateTime.now();
    }

    /**
     * 관리자에 의한 로그인 차단(정지). 본인 탈퇴(deactivate)와 달리 PII를 그대로 유지하며,
     * activate()로 다시 되돌릴 수 있다.
     */
    public void suspend() {
        this.status = UserStatus.SUSPENDED;
    }

    public void activate() {
        this.status = UserStatus.ACTIVE;
    }
}
