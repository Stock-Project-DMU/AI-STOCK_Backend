package com.teamfp.aistock.domain.order.entity;

import java.time.LocalDateTime;

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

    public void execute(long execPrice) {
        this.execPrice = execPrice;
        this.status = OrderStatus.EXECUTED;
        this.executedAt = LocalDateTime.now();
    }

    public void cancel() {
        this.status = OrderStatus.CANCELLED;
    }
}
