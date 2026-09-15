package com.teamfp.aistock.domain.account.entity;

import java.time.LocalDateTime;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

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
 * 계좌 잔고 변동 원장(ADMIN_API_BACKEND_HANDOFF.md 4.3, feature/admin-api-p0 — 새 테이블 3개 중
 * 하나, 2026-09-07 사용자 승인으로 신규 생성). 문서 권장대로 append-only다 — 수정·삭제 메서드를
 * 두지 않는다. 한 행은 "그 순간 balance가 amount만큼 바뀌어 balanceBefore에서 balanceAfter가
 * 됐다"는 사실만 기록한다.
 *
 * relatedOrderId/relatedChargeRequestId/processedBy는 FK가 아니라 단순 참조용 ID 컬럼이다 —
 * 주문 체결(ORDER_BUY/SELL)마다 매번 User/Order를 fetch join할 필요는 없고, "어느 주문/충전요청
 * 때문에 생긴 변동인지"만 알면 충분하기 때문이다(감사 로그처럼 행위자 신원 자체가 핵심인
 * 테이블과는 성격이 다르다).
 */
@Entity
@Table(name = "account_transactions", indexes = {
        @Index(name = "idx_account_transaction_account", columnList = "account_id, created_at")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class AccountTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "transaction_id")
    private Long transactionId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private AccountTransactionType type;

    // 증감액. 잔고가 늘면 양수, 줄면 음수 — balanceAfter - balanceBefore와 항상 같다.
    @Column(name = "amount", nullable = false)
    private long amount;

    @Column(name = "balance_before", nullable = false)
    private long balanceBefore;

    @Column(name = "balance_after", nullable = false)
    private long balanceAfter;

    @Column(name = "related_order_id")
    private Long relatedOrderId;

    @Column(name = "related_charge_request_id")
    private Long relatedChargeRequestId;

    // 관리자가 개입한 변동(ADMIN_CHARGE/ADMIN_DEDUCTION)에만 채워진다.
    @Column(name = "processed_by")
    private Long processedBy;

    @Column(name = "reason", length = 500)
    private String reason;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    private AccountTransaction(Account account, AccountTransactionType type, long amount, long balanceBefore,
            long balanceAfter, Long relatedOrderId, Long relatedChargeRequestId, Long processedBy, String reason) {
        this.account = account;
        this.type = type;
        this.amount = amount;
        this.balanceBefore = balanceBefore;
        this.balanceAfter = balanceAfter;
        this.relatedOrderId = relatedOrderId;
        this.relatedChargeRequestId = relatedChargeRequestId;
        this.processedBy = processedBy;
        this.reason = reason;
    }
}
