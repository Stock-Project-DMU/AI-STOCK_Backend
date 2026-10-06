package com.teamfp.aistock.domain.stock.entity;

import java.time.LocalDate;
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
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 배당락일 기준 보유 수량으로 확정된 계좌별 배당 권리(feature/dividend). DividendEntitlementJob이
 * 배당락일 장 시작 전(06:00)에 그 시점 보유 수량 = 전 거래일 종가 기준 보유 수량으로 만든다.
 *
 * accountId는 FK가 아닌 단순 참조 ID다(AccountTransaction.relatedOrderId와 같은 방식) — accounts에
 * FK를 걸면 회원 탈퇴 시 계좌 삭제가 이 테이블에 막히거나, 탈퇴 로직에 삭제 순서를 하나 더 챙겨야
 * 한다. 지급 시점에 계좌가 없으면 DividendPaymentJob이 SKIPPED로 닫는다.
 */
@Entity
@Table(name = "dividend_entitlements", indexes = {
        @Index(name = "idx_dividend_entitlement_status", columnList = "status"),
        @Index(name = "idx_dividend_entitlement_account", columnList = "account_id, status")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uq_dividend_entitlement", columnNames = {"account_id", "dividend_schedule_id"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class DividendEntitlement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "dividend_entitlement_id")
    private Long dividendEntitlementId;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dividend_schedule_id", nullable = false)
    private DividendSchedule dividendSchedule;

    @Column(name = "stock_code", length = 10, nullable = false)
    private String stockCode;

    // 배당락일 기준 보유 수량
    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "dps_cash", nullable = false)
    private int dpsCash;

    // quantity × dpsCash. 대량 보유 시 INT 범위(약 21억)를 넘을 수 있어 BIGINT로 둔다.
    @Column(name = "total_amount", nullable = false)
    private long totalAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DividendEntitlementStatus status;

    @Column(name = "ex_dividend_date", nullable = false)
    private LocalDate exDividendDate;

    // 권리 생성 시점에 공시된 지급일(없으면 null — resolvePayDate()가 대체값을 계산)
    @Column(name = "pay_date")
    private LocalDate payDate;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    private DividendEntitlement(Long accountId, DividendSchedule dividendSchedule, int quantity) {
        this.accountId = accountId;
        this.dividendSchedule = dividendSchedule;
        this.stockCode = dividendSchedule.getStockCode();
        this.quantity = quantity;
        this.dpsCash = dividendSchedule.getDpsCash();
        this.totalAmount = (long) quantity * dividendSchedule.getDpsCash();
        this.status = DividendEntitlementStatus.PENDING;
        this.exDividendDate = dividendSchedule.getExDividendDate();
        this.payDate = dividendSchedule.getPayDate();
    }

    /**
     * 실제 지급할 날짜. 권리 생성 시점의 지급일이 있으면 그 값을, 없으면 회차의 최신 지급일(그 뒤
     * 재적재로 공시된 지급일이 들어왔을 수 있음) 또는 기준일 + 45일을 쓴다.
     */
    public LocalDate resolvePayDate() {
        return payDate != null ? payDate : dividendSchedule.resolvePayDate();
    }

    public boolean isPending() {
        return status == DividendEntitlementStatus.PENDING;
    }

    public void markPaid(LocalDateTime paidAt) {
        this.status = DividendEntitlementStatus.PAID;
        this.paidAt = paidAt;
    }

    public void markSkipped() {
        this.status = DividendEntitlementStatus.SKIPPED;
    }
}
