package com.teamfp.aistock.domain.ai.dto;

import com.teamfp.aistock.domain.ai.dto.response.SimulationResponse;

/**
 * 저장 버튼을 누르기 전까지 Redis(simulation:pending:{userId}:{pendingSimulationId}, 30분)에
 * 보관하는 실행 결과. 화면에 내려준 결과(result)와, 화면에는 내려주지 않지만 저장 시
 * simulations.dart_data/news_data 컬럼에 함께 기록할 근거 데이터 원본(JSON 문자열)을 묶는다.
 */
public record PendingSimulationDto(
        SimulationResponse result,
        String dartData,
        String newsData
) {
}
