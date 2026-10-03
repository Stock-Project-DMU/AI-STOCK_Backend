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

/**
 * 저장된 목표 도달 시뮬레이션(feature/goal-simulation-v2, 2026-10-01 — 단일 종목 구조에서
 * "보유종목 유지 vs 리밸런싱" 비교 구조로 바꿈, simulation_v2_migration.sql 참고).
 * 실행할 때마다 저장하지 않고 사용자가 저장 버튼을 눌렀을 때만 행이 생긴다.
 */
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

    @Column(name = "goal_text", length = 200, nullable = false)
    private String goalText;

    @Column(name = "target_amount", nullable = false)
    private long targetAmount;

    // 목표 기한(개월). "1억 만들기"처럼 기한 없는 목표면 null
    @Column(name = "period_months")
    private Integer periodMonths;

    @Column(name = "start_amount", nullable = false)
    private long startAmount;

    @Column(name = "monthly_contribution", nullable = false)
    private long monthlyContribution;

    @Column(name = "current_reach_date")
    private LocalDate currentReachDate;

    @Column(name = "rebalanced_reach_date")
    private LocalDate rebalancedReachDate;

    @Column(name = "projection_data", nullable = false, columnDefinition = "json")
    private String projectionData;

    @Column(name = "rebalance_reason", nullable = false, columnDefinition = "TEXT")
    private String rebalanceReason;

    @Column(name = "time_reduction_explanation", nullable = false, columnDefinition = "TEXT")
    private String timeReductionExplanation;

    @Column(name = "dart_data", columnDefinition = "json")
    private String dartData;

    @Column(name = "news_data", columnDefinition = "json")
    private String newsData;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    private Simulation(User user, String goalText, long targetAmount, Integer periodMonths, long startAmount,
                       long monthlyContribution, LocalDate currentReachDate, LocalDate rebalancedReachDate,
                       String projectionData, String rebalanceReason, String timeReductionExplanation,
                       String dartData, String newsData) {
        this.user = user;
        this.goalText = goalText;
        this.targetAmount = targetAmount;
        this.periodMonths = periodMonths;
        this.startAmount = startAmount;
        this.monthlyContribution = monthlyContribution;
        this.currentReachDate = currentReachDate;
        this.rebalancedReachDate = rebalancedReachDate;
        this.projectionData = projectionData;
        this.rebalanceReason = rebalanceReason;
        this.timeReductionExplanation = timeReductionExplanation;
        this.dartData = dartData;
        this.newsData = newsData;
    }
}
