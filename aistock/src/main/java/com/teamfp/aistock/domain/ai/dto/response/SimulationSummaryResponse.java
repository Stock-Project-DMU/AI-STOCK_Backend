package com.teamfp.aistock.domain.ai.dto.response;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.teamfp.aistock.domain.ai.entity.Simulation;

/**
 * 저장된 시뮬레이션 목록(불러오기 모달)용 요약. 곡선(최대 361개 포인트 × 2)까지 목록에 실으면
 * 응답이 커져서, 목록에는 요약만 내리고 선택한 항목만 상세 조회(GET /api/simulations/{id})한다.
 */
public record SimulationSummaryResponse(
        Long simulationId,
        String goalText,
        long targetAmount,
        Integer periodMonths,
        LocalDate currentReachDate,
        LocalDate rebalancedReachDate,
        LocalDateTime createdAt
) {

    public static SimulationSummaryResponse from(Simulation simulation) {
        return new SimulationSummaryResponse(
                simulation.getSimulationId(),
                simulation.getGoalText(),
                simulation.getTargetAmount(),
                simulation.getPeriodMonths(),
                simulation.getCurrentReachDate(),
                simulation.getRebalancedReachDate(),
                simulation.getCreatedAt()
        );
    }
}
