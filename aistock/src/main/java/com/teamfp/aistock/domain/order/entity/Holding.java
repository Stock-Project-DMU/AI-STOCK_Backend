package com.teamfp.aistock.domain.order.entity;

import java.time.LocalDateTime;

import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.teamfp.aistock.domain.account.entity.Account;

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
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "holdings",
        uniqueConstraints = @UniqueConstraint(name = "uq_account_stock", columnNames = {"account_id", "stock_code"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class Holding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "holding_id")
    private Long holdingId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Column(name = "stock_code", length = 10, nullable = false)
    private String stockCode;

    @Column(name = "stock_name", length = 50, nullable = false)
    private String stockName;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "avg_price", nullable = false)
    private long avgPrice;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    private Holding(Account account, String stockCode, String stockName, int quantity, long avgPrice) {
        this.account = account;
        this.stockCode = stockCode;
        this.stockName = stockName;
        this.quantity = quantity;
        this.avgPrice = avgPrice;
    }

    /**
     * 매수 체결 시 보유수량을 늘리고 평단가를 가중평균으로 재계산한다.
     * 예: 10주를 평단가 1000원에 들고 있는 상태에서 5주를 2000원에 추가 매수하면,
     * 새 평단가 = (1000*10 + 2000*5) / 15 = 1333원(원 단위 내림)이 된다.
     */
    public void increase(int quantity, long execPrice) {
        long totalCost = (this.avgPrice * this.quantity) + (execPrice * quantity);
        this.quantity += quantity;
        this.avgPrice = totalCost / this.quantity;
    }

    /**
     * 매도 체결 시 보유수량을 줄인다. 평단가는 매도로 바뀌지 않는다(남은 주식의 취득 단가는 그대로).
     * 수량이 0이 되면 이 메서드만으로는 행을 지우지 않으므로, 호출하는 서비스 쪽에서
     * quantity == 0인지 확인하고 HoldingRepository.delete()로 정리해야 한다.
     */
    public void decrease(int quantity) {
        this.quantity -= quantity;
    }

    /**
     * 수익률/보유종목 화면에서 쓸 평가용 현재가를 반환한다. Redis stock:price 캐시가 비어있으면
     * (장 마감 등으로 최근 tick이 없으면) avgPrice로 대체해, 캐시 미스 하나 때문에 조회 화면
     * 전체가 실패하지 않도록 한다. 이 폴백 정책은 AccountService.getProfit()/
     * OrderService.getMyHoldings() 양쪽에서 동일하게 써야 하므로 Holding 자신이 갖고 있는
     * avgPrice를 기준으로 이 엔티티에 둔다(같은 로직을 두 서비스에 각각 복붙하지 않기 위함).
     *
     * StockPriceDto(stock 도메인의 DTO) 대신 Long을 받는 이유: order 도메인 엔티티가 다른
     * 도메인의 DTO 타입에 직접 의존하지 않도록, "캐시에서 현재가를 꺼내는" 책임은 호출부
     * (AccountService/OrderService, 이미 StockPriceDto를 쓰고 있음)에 남겨두고 이 엔티티는
     * "현재가가 있는지 없는지"만 안다.
     */
    public long resolveValuationPrice(Long currentPrice) {
        return currentPrice != null ? currentPrice : this.avgPrice;
    }
}
