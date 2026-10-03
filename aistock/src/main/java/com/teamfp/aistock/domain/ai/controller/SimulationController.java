package com.teamfp.aistock.domain.ai.controller;

import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.teamfp.aistock.domain.ai.dto.request.SaveSimulationRequest;
import com.teamfp.aistock.domain.ai.dto.request.SimulationRequest;
import com.teamfp.aistock.domain.ai.dto.response.SimulationResponse;
import com.teamfp.aistock.domain.ai.dto.response.SimulationSummaryResponse;
import com.teamfp.aistock.domain.ai.service.SimulationService;
import com.teamfp.aistock.global.response.ApiResponse;
import com.teamfp.aistock.global.util.SecurityUtil;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 목표 도달 시뮬레이션(feature/goal-simulation-v2, 2026-10-01). 실행(POST)은 결과를 Redis에
 * 30분만 보관하고, 저장(POST /saved)을 해야 DB에 기록된다. 목록·상세·삭제는 저장된 결과 대상이다.
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

    @PostMapping("/saved")
    public ApiResponse<SimulationResponse> saveSimulation(@Valid @RequestBody SaveSimulationRequest request) {
        Long userId = SecurityUtil.getCurrentUserId();
        return ApiResponse.success(simulationService.saveSimulation(userId, request));
    }

    @GetMapping
    public ApiResponse<List<SimulationSummaryResponse>> getMySimulations() {
        Long userId = SecurityUtil.getCurrentUserId();
        return ApiResponse.success(simulationService.getMySimulations(userId));
    }

    @GetMapping("/{simulationId}")
    public ApiResponse<SimulationResponse> getSimulation(@PathVariable Long simulationId) {
        Long userId = SecurityUtil.getCurrentUserId();
        return ApiResponse.success(simulationService.getSimulation(userId, simulationId));
    }

    @DeleteMapping("/{simulationId}")
    public ApiResponse<Void> deleteSimulation(@PathVariable Long simulationId) {
        Long userId = SecurityUtil.getCurrentUserId();
        simulationService.deleteSimulation(userId, simulationId);
        return ApiResponse.success("삭제했습니다.", null);
    }
}
