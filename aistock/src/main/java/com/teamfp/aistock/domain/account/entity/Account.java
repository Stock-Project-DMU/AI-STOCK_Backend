package com.teamfp.aistock.domain.account.entity;

import java.math.BigDecimal;
import java.math.RoundingMode;
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

    // 사용자가 스스로 충전할 수 있는 최대 횟수. 이 횟수를 다 쓴 뒤부터는 충전 요청
    // (ChargeRequestService)으로 관리자 승인을 받아야 한다.
    public static final int MAX_CHARGE_COUNT = 3;
    // 계좌 예치금(현금 = balance + frozenBalance) 최대 보유액 1조원. 충전(직접 충전·관리자 승인 충전·관리자
    // 증액)으로는 이 한도를 넘길 수 없다. 매도 대금·이자처럼 투자 결과로 늘어나는 건 막지 않는다.
    public static final long MAX_DEPOSIT_AMOUNT = 1_000_000_000_000L;
    // 예치금 이자율(연 %). 계좌 개설 시점에 이 값으로 고정된다.
    public static final BigDecimal DEFAULT_INTEREST_RATE = new BigDecimal("0.50");
    // 연이율(%)을 월 이자로 바꿀 때의 분모 — 100(퍼센트) × 12(개월).
    private static final BigDecimal MONTHLY_INTEREST_DIVISOR = BigDecimal.valueOf(1200);

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

    // 사용자 직접 충전 횟수(최대 MAX_CHARGE_COUNT회 — 초과분은 관리자 승인 필요). 검증은 AccountService에서 한다.
    @Column(name = "charge_count", nullable = false)
    private int chargeCount;

    // 예치금 이자율(연 %, 예: 0.50). 매월 1일 AccountInterestJob이 balance(예치금) 기준으로
    // 한 달치 이자를 지급한다. @ColumnDefault는 ddl-auto=update로 컬럼이 추가될 때 기존 계좌에도
    // 0.50이 채워지도록 하기 위함이다.
    @Column(name = "interest_rate", nullable = false, precision = 5, scale = 2)
    @ColumnDefault("0.50")
    private BigDecimal interestRate;

    // 지금까지 지급받은 예치금 이자 누계(원). applyInterest()가 지급할 때마다 더한다. 계좌 조회 응답이
    // 충전 직후 응답 등 여러 곳에서 재사용돼, 원장을 매번 합산하지 않고 컬럼으로 들고 있는다.
    @Column(name = "total_interest", nullable = false)
    @ColumnDefault("0")
    private long totalInterest;

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
        this.interestRate = DEFAULT_INTEREST_RATE;
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
     * 매도 체결 거래 수수료 차감. applySellOrder()로 매도 대금을 넣은 뒤 호출한다. 수수료는 실제
     * 비용이므로 baseBalance는 건드리지 않는다 — 그대로 수익률에 손실로 반영된다.
     */
    public void applyTradeFee(long fee) {
        this.balance -= fee;
    }

    /**
     * amount를 충전해도 예치금(balance + frozenBalance)이 MAX_DEPOSIT_AMOUNT 이하인지 여부.
     */
    public boolean canDeposit(long amount) {
        // balance + frozenBalance + amount를 그대로 더하면 아주 큰 amount에서 long이 넘쳐 음수가 돼 검사를
        // 통과해버린다(코드리뷰 반영). 남은 한도와 비교하는 방식은 넘침이 없다.
        return amount <= MAX_DEPOSIT_AMOUNT - this.balance - this.frozenBalance;
    }

    /**
     * 사용자가 스스로 충전할 수 있는 횟수가 남아 있는지 여부.
     */
    public boolean hasRemainingChargeCount() {
        return this.chargeCount < MAX_CHARGE_COUNT;
    }

    /**
     * balance(예치금) 기준 한 달치 이자 = balance × 연이율(%) ÷ 1200, 원 단위 미만 버림.
     * 지정가 주문에 묶인 frozenBalance는 예치금이 아니므로 제외한다.
     */
    public long calculateMonthlyInterest() {
        return BigDecimal.valueOf(this.balance)
                .multiply(this.interestRate)
                .divide(MONTHLY_INTEREST_DIVISOR, 0, RoundingMode.DOWN)
                .longValue();
    }

    /**
     * 예치금 이자 지급. 충전과 마찬가지로 baseBalance도 같은 금액만큼 올려, 이자가 수익률
     * 계산식 (총자산-baseBalance)/baseBalance에 수익으로 잡히지 않게 한다.
     */
    public void applyInterest(long amount) {
        this.balance += amount;
        this.baseBalance += amount;
        this.totalInterest += amount;
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
     * 부풀어 보이는 문제가 생긴다. 최대 충전 횟수(MAX_CHARGE_COUNT) 검증은 이 메서드가 아니라
     * AccountService.chargeBalance()에서 한다 — Entity는 잔고/횟수 필드를 바꾸는 책임만 갖는다.
     */
    public void chargeBalance(long chargeAmount) {
        this.balance += chargeAmount;
        this.baseBalance += chargeAmount;
        this.chargeCount += 1;
    }

    /**
     * 관리자 승인 충전(ADMIN_API_BACKEND_HANDOFF.md 4.2)/조정. chargeBalance()와 달리
     * chargeCount를 올리지 않는다 — chargeCount는 "자동 충전 3회 한도" 전용 카운터라, 한도 초과
     * 후 관리자가 별도로 승인해주는 충전과는 별개 개념이다(정확히는 한도를 초과했기 때문에
     * 이 메서드를 타게 되는 경우가 대부분).
     */
    public void applyAdminCharge(long amount) {
        this.balance += amount;
        this.baseBalance += amount;
    }

    /**
     * 관리자 잔고 차감 조정(ADMIN_API_BACKEND_HANDOFF.md 4.3 POST .../adjustments,
     * type=ADMIN_DEDUCTION). applyAdminCharge()와 대칭 — baseBalance도 함께 낮춰야 수익률
     * 계산식(총자산-baseBalance)/baseBalance가 왜곡되지 않는다.
     */
    public void applyAdminDeduction(long amount) {
        this.balance -= amount;
        this.baseBalance -= amount;
    }
}
