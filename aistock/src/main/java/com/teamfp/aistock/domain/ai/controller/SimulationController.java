package com.teamfp.aistock.domain.ai.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.teamfp.aistock.domain.ai.dto.request.SimulationRequest;
import com.teamfp.aistock.domain.ai.dto.response.SimulationResponse;
import com.teamfp.aistock.domain.ai.service.SimulationService;
import com.teamfp.aistock.global.response.ApiResponse;
import com.teamfp.aistock.global.util.SecurityUtil;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * feature/simulation-integration — 1차 PR(NAMING.md 8-11절)의 조회 API에 이어
 * runSimulation(Gemini/DART/뉴스 연동)을 붙인 POST 엔드포인트를 추가한다.
 */
@RestController
@RequestMapping("/api/simulations")
@RequiredArgsConstructor
public class SimulationController {

    private final SimulationService simulationService;

    @PostMapping
    public ApiResponse<SimulationResponse> runSimulation(@Valid @RequestBody SimulationRequest request) {
        Long userId = SecurityUtil.getCurrentUserId();
        return ApiResponse.success(simulationService.runSimulation(userId, request));
    }

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
