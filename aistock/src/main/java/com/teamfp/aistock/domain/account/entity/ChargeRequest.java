package com.teamfp.aistock.domain.account.entity;

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

/**
 * 계좌 자동 충전(최대 3회) 한도를 초과한 사용자가 관리자에게 추가 충전을 요청하는 테이블
 * (ADMIN_API_BACKEND_HANDOFF.md 4.2, feature/admin-api-p0 — 새 테이블 3개 중 하나, 2026-09-07
 * 사용자 승인으로 신규 생성). 승인·거절은 한 번만 가능하다(handoff 요구사항) — `decide()`가
 * PENDING이 아니면 스스로 막지 않고, 호출부(AdminChargeRequestService)가 비관적 락으로 조회한
 * 뒤 상태를 먼저 확인해 `CHARGE_REQUEST_ALREADY_PROCESSED`를 던진다(Order.cancel()과 동일한
 * 책임 분담 — Entity는 상태 전이만, 이미 처리됐는지 판단은 Service가 락과 함께 처리).
 */
@Entity
@Table(name = "charge_requests", indexes = {
        @Index(name = "idx_charge_request_account", columnList = "account_id"),
        @Index(name = "idx_charge_request_status", columnList = "status")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class ChargeRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "request_id")
    private Long requestId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Column(name = "amount", nullable = false)
    private long amount;

    @Column(name = "reason", length = 500, nullable = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private ChargeRequestStatus status;

    // 처리한 관리자. ON DELETE SET NULL과 동일한 개념으로 관리자 탈퇴 후에도 요청 기록 자체는
    // 보존해야 하므로(inquiries.answered_by와 동일한 설계, CLAUDE.md 6번), FK는 nullable이다.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "decided_by")
    private User decidedBy;

    @Column(name = "decision_reason", length = 500)
    private String decisionReason;

    @CreatedDate
    @Column(name = "requested_at", nullable = false, updatable = false)
    private LocalDateTime requestedAt;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Builder
    private ChargeRequest(Account account, long amount, String reason) {
        this.account = account;
        this.amount = amount;
        this.reason = reason;
        this.status = ChargeRequestStatus.PENDING;
    }

    public void approve(User admin, String decisionReason) {
        this.status = ChargeRequestStatus.APPROVED;
        this.decidedBy = admin;
        this.decisionReason = decisionReason;
        this.decidedAt = LocalDateTime.now();
    }

    public void reject(User admin, String decisionReason) {
        this.status = ChargeRequestStatus.REJECTED;
        this.decidedBy = admin;
        this.decisionReason = decisionReason;
        this.decidedAt = LocalDateTime.now();
    }
}
