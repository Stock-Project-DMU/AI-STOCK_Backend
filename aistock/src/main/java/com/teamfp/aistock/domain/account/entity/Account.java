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
        @Index(name = "idx_account_status", columnList = "status"),
        @Index(name = "idx_account_user", columnList = "user_id")
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

    // 유저 1명이 계좌를 최대 3개까지 가질 수 있어(성향별로 나눠 투자) 구분용 이름을 둔다.
    @Column(name = "account_name", length = 50, nullable = false)
    private String accountName;

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

    // 가상캐시 충전 횟수(최대 3회 — 초과분은 관리자 승인 필요). 검증은 AccountService에서 한다.
    @Column(name = "charge_count", nullable = false)
    private int chargeCount;

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
    private Account(User user, String accountName, String accountNumber, LocalDate openedAt, long baseBalance, long balance) {
        this.user = user;
        this.accountName = accountName;
        this.accountNumber = accountNumber;
        this.openedAt = openedAt;
        this.baseBalance = baseBalance;
        this.balance = balance;
        this.frozenBalance = 0L;
        this.chargeCount = 0;
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

    /**
     * 지정가 매수 주문 등록 시, 체결될 경우 필요한 최대 금액(지정가 × 수량)을
     * 즉시 사용 가능한 balance에서 frozenBalance로 옮겨 묶어둔다. 이렇게 미리 묶어두지
     * 않으면 같은 계좌로 여러 건의 지정가 매수 주문을 걸어둔 뒤 실제 잔고보다 많은
     * 금액이 동시에 체결될 수 있다.
     */
    public void freezeForOrder(long amount) {
        this.balance -= amount;
        this.frozenBalance += amount;
    }

    /**
     * 지정가 매수 주문을 취소할 때, freezeForOrder로 묶어둔 금액을 그대로 balance로 되돌린다.
     */
    public void unfreezeForOrder(long amount) {
        this.frozenBalance -= amount;
        this.balance += amount;
    }

    /**
     * 지정가 매수 주문이 체결될 때, 묶어뒀던 frozenAmount(지정가 기준)를 해제하고
     * 실제 체결가 기준 비용(actualAmount)만 확정 지출로 반영한다. 지정가 매수는
     * "현재가 <= 지정가"일 때 체결되므로 actualAmount는 항상 frozenAmount 이하이고,
     * 그 차액(frozenAmount - actualAmount)은 balance로 환급된다.
     */
    public void settleFrozenOrder(long frozenAmount, long actualAmount) {
        this.frozenBalance -= frozenAmount;
        this.balance += (frozenAmount - actualAmount);
    }

    /**
     * 가상캐시 충전. balance에 chargeAmount를 더하고(덮어쓰기 아님) baseBalance도 같은 금액만큼
     * 함께 올린다. baseBalance를 같이 올리지 않으면 충전으로 늘어난 현금이 수익률 계산식
     * (총자산-baseBalance)/baseBalance에 그대로 섞여 들어가 실제 투자 성과보다 수익률이
     * 부풀어 보이는 문제가 생긴다. 최대 충전 횟수(3회) 검증은 이 메서드가 아니라
     * AccountService.chargeBalance()에서 한다 — Entity는 잔고/횟수 필드를 바꾸는 책임만 갖는다.
     */
    public void chargeBalance(long chargeAmount) {
        this.balance += chargeAmount;
        this.baseBalance += chargeAmount;
        this.chargeCount += 1;
    }
}
