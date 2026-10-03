package com.teamfp.aistock.domain.ai.dto.response;

import java.time.LocalDateTime;

import com.teamfp.aistock.domain.ai.dto.PortfolioProjectionDto;
import com.teamfp.aistock.domain.ai.dto.ProjectionDataJson;
import com.teamfp.aistock.domain.ai.entity.Simulation;

/**
 * 목표 도달 시뮬레이션 결과(feature/goal-simulation-v2, 2026-10-01).
 *
 * 실행 직후(아직 저장 전)에는 simulationId가 null이고 pendingSimulationId(저장 요청에 쓰는 임시
 * ID, 30분 유효)가 채워진다. 저장했거나 저장된 목록에서 불러온 결과는 그 반대다.
 *
 * @param current                  왼쪽 차트 — 현재 보유종목 그대로 유지
 * @param rebalanced               오른쪽 차트 — 투자성향 기반 리밸런싱
 * @param shortenedMonths          리밸런싱으로 단축되는 개월 수(ProjectionDataJson.shortenedMonths와 동일 규칙)
 * @param rebalanceReason          Gemini가 작성한 리밸런싱 이유
 * @param timeReductionExplanation Gemini가 작성한 목표 도달 시간 단축 설명
 */
public record SimulationResponse(
        Long simulationId,
        String pendingSimulationId,
        String goalText,
        long targetAmount,
        Integer periodMonths,
        long startAmount,
        long holdingsAmount,
        long cashAmount,
        long monthlyContribution,
        PortfolioProjectionDto current,
        PortfolioProjectionDto rebalanced,
        Integer shortenedMonths,
        String rebalanceReason,
        String timeReductionExplanation,
        LocalDateTime createdAt
) {

    public static SimulationResponse of(Simulation simulation, ProjectionDataJson projectionData) {
        return new SimulationResponse(
                simulation.getSimulationId(),
                null,
                simulation.getGoalText(),
                simulation.getTargetAmount(),
                simulation.getPeriodMonths(),
                simulation.getStartAmount(),
                projectionData.holdingsAmount(),
                projectionData.cashAmount(),
                simulation.getMonthlyContribution(),
                projectionData.current(),
                projectionData.rebalanced(),
                projectionData.shortenedMonths(),
                simulation.getRebalanceReason(),
                simulation.getTimeReductionExplanation(),
                simulation.getCreatedAt()
        );
    }
}
