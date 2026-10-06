package com.teamfp.aistock.domain.stock.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 종목별 배당 회차(feature/dividend). local-market-data-generator의 dividends.json을
 * DividendScheduleService.reloadSchedules()가 (stockCode, fiscalYear, period) 기준으로 UPSERT한다.
 *
 * dpsCash·payDate는 배당결정 공시 전이면 null이다 — 공시가 나온 뒤 파일을 다시 만들고 재적재하면
 * {@link #refresh}로 채워진다. dpsCash가 null인 회차는 배당락일이 와도 배당 권리를 만들지 않는다
 * (DividendEntitlementJob 참고).
 */
@Entity
@Table(name = "dividend_schedules", indexes = {
        @Index(name = "idx_dividend_schedule_ex_date", columnList = "ex_dividend_date")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uq_dividend_schedule", columnNames = {"stock_code", "fiscal_year", "period"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class DividendSchedule {

    // payDate가 공시되지 않은 회차의 지급일 대체값 = 배당 기준일 + 45일. 국내 결산배당은 주주총회
    // 후 1개월 이내 지급이라 기준일(12/31)로부터 대략 3월 말~4월 중순이 되는데, 분기배당(기준일 후
    // 1개월 남짓)과 결산배당 사이의 보수적인 중간값으로 잡았다.
    public static final int PAY_DATE_FALLBACK_DAYS = 45;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "dividend_schedule_id")
    private Long dividendScheduleId;

    @Column(name = "stock_code", length = 10, nullable = false)
    private String stockCode;

    @Column(name = "fiscal_year", length = 4, nullable = false)
    private String fiscalYear;

    // Q1~Q4(분기 결산기준일 기준 회차). 연 1회 배당도 결산기 회차인 Q4로 들어온다.
    @Column(name = "period", length = 10, nullable = false)
    private String period;

    // ANNUAL(그 해 배당 1회) / QUARTERLY(2회 이상 — 반기배당 포함)
    @Column(name = "dividend_kind", length = 20)
    private String dividendKind;

    @Column(name = "dps_cash")
    private Integer dpsCash;

    @Column(name = "record_date")
    private LocalDate recordDate;

    @Column(name = "ex_dividend_date")
    private LocalDate exDividendDate;

    @Column(name = "pay_date")
    private LocalDate payDate;

    @Column(name = "dividend_yield", precision = 5, scale = 2)
    private BigDecimal dividendYield;

    @Column(name = "source", length = 50)
    private String source;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    private DividendSchedule(String stockCode, String fiscalYear, String period, String dividendKind, Integer dpsCash,
            LocalDate recordDate, LocalDate exDividendDate, LocalDate payDate, BigDecimal dividendYield, String source) {
        this.stockCode = stockCode;
        this.fiscalYear = fiscalYear;
        this.period = period;
        this.dividendKind = dividendKind;
        this.dpsCash = dpsCash;
        this.recordDate = recordDate;
        this.exDividendDate = exDividendDate;
        this.payDate = payDate;
        this.dividendYield = dividendYield;
        this.source = source;
    }

    /**
     * 재적재 시 같은 회차(stockCode, fiscalYear, period)의 값을 파일 최신값으로 덮어쓴다. 바뀐 값이
     * 하나라도 있으면 true — 재적재 로그의 "업데이트 N건" 집계에 쓴다.
     */
    public boolean refresh(String dividendKind, Integer dpsCash, LocalDate recordDate, LocalDate exDividendDate,
            LocalDate payDate, BigDecimal dividendYield, String source) {
        boolean isChanged = !Objects.equals(this.dividendKind, dividendKind)
                || !Objects.equals(this.dpsCash, dpsCash)
                || !Objects.equals(this.recordDate, recordDate)
                || !Objects.equals(this.exDividendDate, exDividendDate)
                || !Objects.equals(this.payDate, payDate)
                || compareYield(this.dividendYield, dividendYield) != 0
                || !Objects.equals(this.source, source);
        if (!isChanged) {
            return false;
        }
        this.dividendKind = dividendKind;
        this.dpsCash = dpsCash;
        this.recordDate = recordDate;
        this.exDividendDate = exDividendDate;
        this.payDate = payDate;
        this.dividendYield = dividendYield;
        this.source = source;
        return true;
    }

    /**
     * 실제 지급할 날짜: 공시된 지급일, 없으면 배당 기준일 + {@value #PAY_DATE_FALLBACK_DAYS}일.
     * 기준일까지 없으면(현재 생성 로직상 배당락일이 있는 회차는 항상 기준일도 있음) 배당락일 기준으로 대체한다.
     */
    public LocalDate resolvePayDate() {
        if (payDate != null) {
            return payDate;
        }
        LocalDate baseDate = recordDate != null ? recordDate : exDividendDate;
        return baseDate != null ? baseDate.plusDays(PAY_DATE_FALLBACK_DAYS) : null;
    }

    public boolean isPayDateEstimated() {
        return payDate == null;
    }

    // DECIMAL(5,2) 컬럼에서 다시 읽으면 0.4가 0.40이 되는 등 scale만 달라 equals가 false가 되므로 값만 비교한다.
    private static int compareYield(BigDecimal current, BigDecimal incoming) {
        if (current == null || incoming == null) {
            return current == incoming ? 0 : 1;
        }
        return current.compareTo(incoming);
    }
}
