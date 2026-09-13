package com.teamfp.aistock.domain.ai.controller;
import com.teamfp.aistock.domain.ai.dto.request.PlanningPreferencesRequest;
import com.teamfp.aistock.domain.ai.service.PlanningPreferencesService;
import com.teamfp.aistock.global.response.ApiResponse;
import com.teamfp.aistock.global.util.SecurityUtil;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/ai/planning/preferences") @RequiredArgsConstructor
public class PlanningPreferencesController {
    private final PlanningPreferencesService planningPreferencesService;
    @GetMapping public ApiResponse<PlanningPreferencesRequest> getPreferences() {
        return ApiResponse.success(planningPreferencesService.getPreferences(SecurityUtil.getCurrentUserId()));
    }
    @PutMapping public ApiResponse<PlanningPreferencesRequest> savePreferences(@Valid @RequestBody PlanningPreferencesRequest request) {
        return ApiResponse.success(planningPreferencesService.savePreferences(SecurityUtil.getCurrentUserId(), request));
    }
}
