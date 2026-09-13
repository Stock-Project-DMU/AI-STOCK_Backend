package com.teamfp.aistock.domain.ai.entity;

import com.teamfp.aistock.domain.ai.dto.request.GoalPlanRequest;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "goal_plans", indexes = @Index(name = "idx_goal_plan_user", columnList = "user_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GoalPlan {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long planId;
    @Column(nullable = false) private Long userId;
    @Column(nullable = false, length = 20) private String goal;
    @Column(nullable = false) private long monthlyPayment;
    @Column(nullable = false) private int years;
    @Column(nullable = false) private double annualReturn;
    @Column(nullable = false) private boolean aggressive;
    @Column(name = "is_saved", nullable = false) private boolean saved;
    @Column(nullable = false) private LocalDateTime createdAt;

    public static GoalPlan from(Long userId, GoalPlanRequest request) {
        GoalPlan plan = new GoalPlan();
        plan.userId = userId;
        plan.goal = request.goal();
        plan.monthlyPayment = request.monthlyPayment();
        plan.years = request.years();
        plan.annualReturn = request.annualReturn();
        plan.aggressive = request.aggressive();
        plan.createdAt = LocalDateTime.now();
        return plan;
    }
    public void savePlan() { saved = true; }
}
