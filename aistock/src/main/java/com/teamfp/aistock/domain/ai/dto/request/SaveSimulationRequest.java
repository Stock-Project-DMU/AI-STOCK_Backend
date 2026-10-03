package com.teamfp.aistock.domain.ai.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * 시뮬레이션 저장 요청 DTO. 결과 자체가 아니라 실행 시 서버가 발급한 임시 ID만 받는다 —
 * 결과는 서버가 Redis에 보관해둔 값을 그대로 저장한다(RedisPendingSimulationService 참고).
 */
public record SaveSimulationRequest(

        @NotBlank(message = "저장할 시뮬레이션 ID가 필요합니다.")
        String pendingSimulationId
) {
}
