package com.teamfp.aistock.domain.user.entity;

import java.time.LocalDateTime;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "investment_profile")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class InvestmentProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "profile_id")
    private Long profileId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(name = "investment_tendency", nullable = false)
    private int investmentTendency;

    @Column(name = "fund_tendency", nullable = false)
    private int fundTendency;

    @Enumerated(EnumType.STRING)
    @Column(name = "investment_level", nullable = false, length = 20)
    private InvestmentLevel investmentLevel;

    @Column(name = "survey_answers", columnDefinition = "json")
    private String surveyAnswers;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    private InvestmentProfile(User user, int investmentTendency, int fundTendency,
                               InvestmentLevel investmentLevel, String surveyAnswers) {
        this.user = user;
        this.investmentTendency = investmentTendency;
        this.fundTendency = fundTendency;
        this.investmentLevel = investmentLevel != null ? investmentLevel : InvestmentLevel.BEGINNER;
        this.surveyAnswers = surveyAnswers;
    }

    /**
     * 투자성향 설문 재제출. investmentLevel(초보자/중급자/전문가)은 이 설문으로 바꾸지 않는다 —
     * 별도 평가 로직은 이번 범위 밖이라 기존 값을 그대로 둔다.
     */
    public void updateSurvey(int investmentTendency, int fundTendency, String surveyAnswers) {
        this.investmentTendency = investmentTendency;
        this.fundTendency = fundTendency;
        this.surveyAnswers = surveyAnswers;
    }
}
