package com.teamfp.aistock.domain.ai.dto.response;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import com.teamfp.aistock.domain.ai.dto.ScenarioPointDto;
import com.teamfp.aistock.domain.ai.entity.Simulation;

public record SimulationResponse(
        Long simulationId,
        String stockCode,
        String stockName,
        long investmentAmount,
        long targetAmount,
        int targetMonths,
        List<ScenarioPointDto> bestScenario,
        List<ScenarioPointDto> baseScenario,
        List<ScenarioPointDto> worstScenario,
        LocalDate bestReachDate,
        LocalDate baseReachDate,
        LocalDate worstReachDate,
        LocalDateTime createdAt
) {

    public static SimulationResponse of(Simulation simulation, List<ScenarioPointDto> bestScenario,
                                         List<ScenarioPointDto> baseScenario, List<ScenarioPointDto> worstScenario) {
        return new SimulationResponse(
                simulation.getSimulationId(),
                simulation.getStockCode(),
                simulation.getStockName(),
                simulation.getInvestmentAmount(),
                simulation.getTargetAmount(),
                simulation.getTargetMonths(),
                bestScenario,
                baseScenario,
                worstScenario,
                simulation.getBestReachDate(),
                simulation.getBaseReachDate(),
                simulation.getWorstReachDate(),
                simulation.getCreatedAt()
        );
    }
}
