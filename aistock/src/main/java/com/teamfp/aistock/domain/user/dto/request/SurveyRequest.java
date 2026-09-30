package com.teamfp.aistock.domain.user.dto.request;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;

/**
 * 투자성향 설문 결과 제출 요청 DTO. 투자성향·자금성향·투자 레벨 전부 서버가 답변(answers)으로
 * 직접 계산한다(SurveyTendencyEvaluator, SurveyLevelEvaluator).
 */
public record SurveyRequest(

        @NotEmpty(message = "설문 응답은 필수입니다.")
        List<Integer> answers,
        List<Integer> experienceAnswers
) {
    public SurveyRequest(List<Integer> answers) {
        this(answers, null);
    }
}
