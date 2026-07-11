package com.teamfp.aistock.domain.ai.entity;

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
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "simulations", indexes = {
        @Index(name = "idx_user_sim", columnList = "user_id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class Simulation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "simulation_id")
    private Long simulationId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "stock_code", length = 10, nullable = false)
    private String stockCode;

    @Column(name = "stock_name", length = 50, nullable = false)
    private String stockName;

    @Column(name = "target_amount", nullable = false)
    private long targetAmount;

    @Column(name = "target_months", nullable = false)
    private int targetMonths;

    @Column(name = "scenario_data", nullable = false, columnDefinition = "json")
    private String scenarioData;

    @Column(name = "best_reach_date")
    private LocalDate bestReachDate;

    @Column(name = "base_reach_date")
    private LocalDate baseReachDate;

    @Column(name = "worst_reach_date")
    private LocalDate worstReachDate;

    @Column(name = "dart_data", columnDefinition = "json")
    private String dartData;

    @Column(name = "news_data", columnDefinition = "json")
    private String newsData;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    private Simulation(User user, String stockCode, String stockName, long targetAmount, int targetMonths,
                        String scenarioData, LocalDate bestReachDate, LocalDate baseReachDate,
                        LocalDate worstReachDate, String dartData, String newsData) {
        this.user = user;
        this.stockCode = stockCode;
        this.stockName = stockName;
        this.targetAmount = targetAmount;
        this.targetMonths = targetMonths;
        this.scenarioData = scenarioData;
        this.bestReachDate = bestReachDate;
        this.baseReachDate = baseReachDate;
        this.worstReachDate = worstReachDate;
        this.dartData = dartData;
        this.newsData = newsData;
    }
}
