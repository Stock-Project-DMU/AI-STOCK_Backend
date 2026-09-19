package com.teamfp.aistock.domain.user.dto.request;

import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;

/**
 * 투자성향 설문 결과 제출 요청 DTO. 투자 레벨은 서버에서 금융 지식과 경험 기간 응답으로 계산한다.
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
