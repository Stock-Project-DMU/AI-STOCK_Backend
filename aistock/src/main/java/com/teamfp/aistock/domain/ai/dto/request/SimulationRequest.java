package com.teamfp.aistock.domain.ai.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * 목표 도달 시뮬레이션 요청 DTO. 사용자가 실제 보유한 종목/수량과 무관하게
 * investmentAmount(투자 원금)를 가정해 계산한다.
 *
 * feature/simulation 1차 PR 시점에는 이 DTO를 실제로 받는 POST 엔드포인트가
 * 없다 — Gemini/DART/뉴스 연동(runSimulation)은 feature/ai-planning이 dev에
 * 병합된 뒤 별도 브랜치에서 이어간다. 다음 PR에서 그대로 재사용할 수 있도록
 * 지금 정의만 해둔다(NAMING.md 8-11절 참고).
 */
public record SimulationRequest(

        @NotBlank(message = "종목 코드는 필수입니다.")
        String stockCode,

        @Positive(message = "투자 금액은 0보다 커야 합니다.")
        long investmentAmount,

        @Positive(message = "목표 금액은 0보다 커야 합니다.")
        long targetAmount,

        @Min(value = 1, message = "목표 기간은 최소 1개월입니다.")
        @Max(value = 12, message = "목표 기간은 최대 12개월입니다.")
        int targetMonths
) {
}
