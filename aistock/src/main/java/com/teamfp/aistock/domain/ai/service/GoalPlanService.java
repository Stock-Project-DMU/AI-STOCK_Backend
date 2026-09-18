package com.teamfp.aistock.domain.ai.service;

import com.teamfp.aistock.domain.ai.dto.request.GoalPlanRequest;
import com.teamfp.aistock.domain.ai.dto.response.GoalPlanResponse;
import com.teamfp.aistock.domain.ai.entity.GoalPlan;
import com.teamfp.aistock.domain.ai.repository.GoalPlanRepository;
import com.teamfp.aistock.global.exception.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service @RequiredArgsConstructor @Transactional(readOnly = true)
public class GoalPlanService {
    private final GoalPlanRepository goalPlanRepository;
    public static long calculateFutureValue(long monthlyPayment, int years, double annualReturn) {
        double rate = annualReturn / 1200;
        return Math.round(rate == 0 ? monthlyPayment * (double) years * 12
                : monthlyPayment * Math.expm1(years * 12 * Math.log1p(rate)) / rate);
    }
    @Transactional
    public GoalPlanResponse createPlan(Long userId, GoalPlanRequest request) {
        return toResponse(goalPlanRepository.save(GoalPlan.from(userId, request)));
    }
    public List<GoalPlanResponse> getPlans(Long userId) {
        return goalPlanRepository.findTop100ByUserIdOrderByCreatedAtDesc(userId).stream().map(this::toResponse).toList();
    }
    @Transactional
    public GoalPlanResponse savePlan(Long userId, Long planId) {
        GoalPlan plan = findPlan(userId, planId);
        plan.savePlan();
        return toResponse(plan);
    }
    @Transactional
    public void deletePlan(Long userId, Long planId) { goalPlanRepository.delete(findPlan(userId, planId)); }
    private GoalPlan findPlan(Long userId, Long planId) {
        return goalPlanRepository.findByPlanIdAndUserId(planId, userId)
                .orElseThrow(() -> new CustomException(ErrorCode.INVALID_INPUT));
    }
    private GoalPlanResponse toResponse(GoalPlan plan) {
        return new GoalPlanResponse(plan.getPlanId(), new GoalPlanRequest(plan.getGoal(), plan.getMonthlyPayment(),
                plan.getYears(), plan.getAnnualReturn(), plan.isAggressive()),
                calculateFutureValue(plan.getMonthlyPayment(), plan.getYears(), plan.getAnnualReturn()),
                calculateFutureValue(plan.getMonthlyPayment(), plan.getYears(), plan.getAnnualReturn() + (plan.isAggressive() ? 2.5 : 0)),
                plan.isSaved(), plan.getCreatedAt());
    }
}
