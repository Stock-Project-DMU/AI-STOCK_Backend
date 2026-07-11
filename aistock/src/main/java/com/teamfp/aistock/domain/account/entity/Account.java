package com.teamfp.aistock.domain.account.entity;

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
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "accounts")
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
    }

    public void applyBuyOrder(long amount) {
        this.balance -= amount;
    }
}
