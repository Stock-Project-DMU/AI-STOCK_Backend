package com.teamfp.aistock.domain.account.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.hibernate.annotations.ColumnDefault;
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
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "accounts", indexes = {
        @Index(name = "idx_account_status", columnList = "status")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "account_id")
    private Long accountId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "account_number", length = 20, nullable = false, unique = true)
    private String accountNumber;

    @Column(name = "opened_at", nullable = false)
    private LocalDate openedAt;

    @Column(name = "base_balance", nullable = false)
    private long baseBalance;

    @Column(name = "balance", nullable = false)
    private long balance;

    @Column(name = "frozen_balance", nullable = false)
    private long frozenBalance;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    @ColumnDefault("'ACTIVE'")
    private AccountStatus status;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    private Account(User user, String accountNumber, LocalDate openedAt, long baseBalance, long balance) {
        this.user = user;
        this.accountNumber = accountNumber;
        this.openedAt = openedAt;
        this.baseBalance = baseBalance;
        this.balance = balance;
        this.frozenBalance = 0L;
        this.status = AccountStatus.ACTIVE;
    }

    public void applyBuyOrder(long amount) {
        this.balance -= amount;
    }

    /**
     * 매도 체결로 들어온 대금을 잔고에 더한다. applyBuyOrder와 대칭되는 메서드다.
     * (매도는 frozen_balance를 쓰지 않는다 — 지정가 매수 예약 잠금과 달리, 보유 주식은
     * 이미 보유 중이라 별도로 자금을 미리 묶어둘 필요가 없다.)
     */
    public void applySellOrder(long amount) {
        this.balance += amount;
    }

    /**
     * 관리자에 의한 계좌 거래 정지. 로그인은 그대로 가능하고 매수·매도 주문만 막는다
     * (차단 로직 자체는 OrderService 쪽에서 처리 — 이 메서드는 상태 전환만 담당).
     */
    public void suspend() {
        this.status = AccountStatus.SUSPENDED;
    }

    public void activate() {
        this.status = AccountStatus.ACTIVE;
    }
}
