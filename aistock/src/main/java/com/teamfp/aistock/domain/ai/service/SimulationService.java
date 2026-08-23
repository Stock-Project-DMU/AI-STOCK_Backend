package com.teamfp.aistock.domain.ai.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import com.teamfp.aistock.domain.ai.dto.ScenarioDataJson;
import com.teamfp.aistock.domain.ai.dto.response.SimulationResponse;
import com.teamfp.aistock.domain.ai.entity.Simulation;
import com.teamfp.aistock.domain.ai.repository.SimulationRepository;
import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

/**
 * feature/simulation 1차 PR 범위: 조회만 제공한다. runSimulation(Gemini/DART/뉴스
 * 연동)은 feature/ai-planning이 dev에 병합된 뒤 별도 브랜치에서 이어간다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SimulationService {

    private final SimulationRepository simulationRepository;
    private final ObjectMapper objectMapper;

    public List<SimulationResponse> getMySimulations(Long userId) {
        return simulationRepository.findAllByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * 시뮬레이션 상세 조회. findByUserIdAndSimulationId로 소유권을 함께 검증하여
     * 다른 사용자의 시뮬레이션을 조회할 수 없도록 한다.
     */
    public SimulationResponse getSimulation(Long userId, Long simulationId) {
        Simulation simulation = simulationRepository.findByUserIdAndSimulationId(userId, simulationId)
                .orElseThrow(() -> new CustomException(ErrorCode.SIMULATION_NOT_FOUND));

        return toResponse(simulation);
    }

    private SimulationResponse toResponse(Simulation simulation) {
        ScenarioDataJson scenarioData = parseScenarioData(simulation.getScenarioData());
        return SimulationResponse.of(simulation, scenarioData.best(), scenarioData.base(), scenarioData.worst());
    }

    /**
     * MySQL JSON 컬럼(scenario_data) 파싱 실패는 RedisStockCacheService의 Redis 직렬화
     * 오류 처리와 성격이 같지만(체크 예외를 CustomException으로 감싸 던짐), Redis가 아니라
     * DB 컬럼 파싱이므로 REDIS_SERIALIZATION_ERROR를 재사용하지 않고 별도 코드를 쓴다.
     */
    private ScenarioDataJson parseScenarioData(String scenarioDataJson) {
        try {
            return objectMapper.readValue(scenarioDataJson, ScenarioDataJson.class);
        } catch (JacksonException e) {
            throw new CustomException(ErrorCode.SCENARIO_DATA_PARSE_ERROR, e);
        }
    }
}
