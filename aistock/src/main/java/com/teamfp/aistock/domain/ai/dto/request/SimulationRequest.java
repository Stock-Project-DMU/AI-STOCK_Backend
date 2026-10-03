package com.teamfp.aistock.domain.ai.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 목표 도달 시뮬레이션 실행 요청 DTO(feature/goal-simulation-v2, 2026-10-01).
 * 목표는 자유 문장(goalText)으로 받고, 목표 금액·기한 추출은 서버가 Gemini로 한다
 * (예: "3년 안에 1억" → targetAmount 100000000, periodMonths 36). 시작 금액은 사용자의
 * 실제 보유종목 평가금액 + 예수금이라 요청으로 받지 않는다.
 */
public record SimulationRequest(

        @NotBlank(message = "목표를 입력해 주세요.")
        @Size(max = 200, message = "목표는 200자 이내로 입력해 주세요.")
        String goalText,

        @Min(value = 0, message = "월 추가 납입액은 0원 이상이어야 합니다.")
        @Max(value = 100_000_000, message = "월 추가 납입액은 1억원 이하로 입력해 주세요.")
        long monthlyContribution
) {
}
