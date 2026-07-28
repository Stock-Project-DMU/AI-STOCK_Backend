package com.teamfp.aistock.domain.user.dto.request;

import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;

/**
 * 투자성향 설문 결과 제출 요청 DTO. investmentLevel(초보자/중급자/전문가)은 이 설문으로
 * 바꾸지 않는다 — 별도 평가 로직은 이번 범위 밖이라 기존 값(신규 작성 시 기본 BEGINNER)을 유지한다.
 */
public record SurveyRequest(

        @NotEmpty(message = "설문 응답은 필수입니다.")
        List<Integer> answers,

        @Min(value = 1, message = "투자성향은 1~5 사이여야 합니다.")
        @Max(value = 5, message = "투자성향은 1~5 사이여야 합니다.")
        int investmentTendency,

        @Min(value = 1, message = "자금성향은 1~4 사이여야 합니다.")
        @Max(value = 4, message = "자금성향은 1~4 사이여야 합니다.")
        int fundTendency
) {
}
