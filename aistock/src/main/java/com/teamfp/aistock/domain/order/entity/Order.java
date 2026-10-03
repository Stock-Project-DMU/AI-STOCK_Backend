package com.teamfp.aistock.domain.order.entity;

import java.time.LocalDateTime;

import org.hibernate.annotations.ColumnDefault;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.teamfp.aistock.domain.account.entity.Account;

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
@Table(name = "orders", indexes = {
        @Index(name = "idx_account_order", columnList = "account_id, ordered_at"),
        @Index(name = "idx_stock_pending", columnList = "stock_code, status")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class Order {

    // 매도 거래 수수료율 0.1%를 천분율로 표현한 값(1/1000). 매수에는 수수료가 없다.
    private static final long SELL_FEE_RATE_PER_MILLE = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "order_id")
    private Long orderId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Column(name = "stock_code", length = 10, nullable = false)
    private String stockCode;

    @Column(name = "stock_name", length = 50, nullable = false)
    private String stockName;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_type", nullable = false, length = 10)
    private OrderType orderType;

    @Enumerated(EnumType.STRING)
    @Column(name = "price_type", nullable = false, length = 10)
    private PriceType priceType;

    @Column(name = "order_price", nullable = false)
    private long orderPrice;

    @Column(name = "exec_price")
    private Long execPrice;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    // 거래 수수료(원). 매도 체결 시에만 체결금액 × 0.1%(원 단위 미만 버림)가 채워지고, 매수·미체결·
    // 취소 주문은 0이다. @ColumnDefault는 ddl-auto=update로 컬럼이 추가될 때 기존 주문을 0으로 채우기 위함.
    @Column(name = "fee", nullable = false)
    @ColumnDefault("0")
    private long fee;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private OrderStatus status;

    // v9 추가: 낙관적 락(accounts.version과 동일한 목적). Order 행 자체를 findByIdForUpdate의
    // 비관적 락으로 보호하는 것이 체결/취소 경합을 막는
    // 주된 방법이지만, OrderRepository에는 그 외에도 잠금 없는 조회 메서드(관리자 기능 등)가
    // 함께 존재해 그 경로로 조회한 뒤 execute()/cancel()을 호출하는 코드가 나중에 추가되더라도
    // JPA가 자동으로 동시 수정 충돌을 막아주는 최후의 안전망 역할을 한다.
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @CreatedDate
    @Column(name = "ordered_at", nullable = false, updatable = false)
    private LocalDateTime orderedAt;

    @Column(name = "executed_at")
    private LocalDateTime executedAt;

    @Builder
    private Order(Account account, String stockCode, String stockName, OrderType orderType,
                   PriceType priceType, long orderPrice, int quantity) {
        this.account = account;
        this.stockCode = stockCode;
        this.stockName = stockName;
        this.orderType = orderType;
        this.priceType = priceType;
        this.orderPrice = orderPrice;
        this.quantity = quantity;
        this.status = OrderStatus.PENDING;
    }

    /**
     * 매도 체결금액에 대한 거래 수수료 = 체결금액 × 0.1%, 원 단위 미만 버림. 잔고 차감
     * (OrderService/OrderExecutionService)과 execute()의 fee 기록이 같은 계산을 쓰도록 한 곳에 둔다.
     */
    public static long calculateSellFee(long tradeAmount) {
        return tradeAmount * SELL_FEE_RATE_PER_MILLE / 1000;
    }

    /**
     * 잔고 변동 원장(account_transactions.reason)에 남길 "종목명 N주" 문구. 계좌 내역 화면이 주문
     * 상세 없이도 어떤 거래였는지 한 줄로 알 수 있게 모든 주문 관련 원장 사유 앞에 붙인다.
     */
    public String describeStockAndQuantity() {
        return this.stockName + " " + this.quantity + "주";
    }

    public void execute(long execPrice) {
        this.execPrice = execPrice;
        this.fee = this.orderType == OrderType.SELL ? calculateSellFee(execPrice * this.quantity) : 0L;
        this.status = OrderStatus.EXECUTED;
        this.executedAt = LocalDateTime.now();
    }

    public void cancel() {
        this.status = OrderStatus.CANCELLED;
    }
}
