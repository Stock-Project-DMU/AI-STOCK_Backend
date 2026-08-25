package com.teamfp.aistock.domain.ai.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.teamfp.aistock.domain.ai.dto.response.SimulationResponse;
import com.teamfp.aistock.domain.ai.service.SimulationService;
import com.teamfp.aistock.global.response.ApiResponse;
import com.teamfp.aistock.global.util.SecurityUtil;

import lombok.RequiredArgsConstructor;

/**
 * feature/simulation 1차 PR 범위: 조회 API만 제공한다. POST /api/simulations
 * (runSimulation, Gemini/DART/뉴스 연동)는 feature/ai-planning이 dev에 병합된 뒤
 * 별도 브랜치에서 이어간다 — NAMING.md 8-11절 참고.
 */
@RestController
@RequestMapping("/api/simulations")
@RequiredArgsConstructor
public class SimulationController {

    private final SimulationService simulationService;

    @GetMapping
    public ApiResponse<List<SimulationResponse>> getMySimulations() {
        Long userId = SecurityUtil.getCurrentUserId();
        return ApiResponse.success(simulationService.getMySimulations(userId));
    }

    @GetMapping("/{simulationId}")
    public ApiResponse<SimulationResponse> getSimulation(@PathVariable Long simulationId) {
        Long userId = SecurityUtil.getCurrentUserId();
        return ApiResponse.success(simulationService.getSimulation(userId, simulationId));
    }
}
