package com.teamfp.aistock.domain.ai.controller;

import com.teamfp.aistock.domain.ai.dto.request.GoalPlanRequest;
import com.teamfp.aistock.domain.ai.dto.response.GoalPlanResponse;
import com.teamfp.aistock.domain.ai.service.GoalPlanService;
import com.teamfp.aistock.global.response.ApiResponse;
import com.teamfp.aistock.global.util.SecurityUtil;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @RequestMapping("/api/goal-plans") @RequiredArgsConstructor
public class GoalPlanController {
    private final GoalPlanService goalPlanService;
    @PostMapping public ApiResponse<GoalPlanResponse> createPlan(@Valid @RequestBody GoalPlanRequest request) {
        return ApiResponse.success(goalPlanService.createPlan(SecurityUtil.getCurrentUserId(), request));
    }
    @GetMapping public ApiResponse<List<GoalPlanResponse>> getPlans() {
        return ApiResponse.success(goalPlanService.getPlans(SecurityUtil.getCurrentUserId()));
    }
    @PatchMapping("/{planId}/saved") public ApiResponse<GoalPlanResponse> savePlan(@PathVariable Long planId) {
        return ApiResponse.success(goalPlanService.savePlan(SecurityUtil.getCurrentUserId(), planId));
    }
    @DeleteMapping("/{planId}") public ApiResponse<Void> deletePlan(@PathVariable Long planId) {
        goalPlanService.deletePlan(SecurityUtil.getCurrentUserId(), planId);
        return ApiResponse.success("삭제했습니다.", null);
    }
}
