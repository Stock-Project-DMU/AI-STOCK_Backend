package com.teamfp.aistock.domain.user.service;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SurveyTendencyEvaluatorTest {

    // 투자성향 가중합산: score = Q2+Q3+2*Q4+Q5+3*Q6+2*Q7 (범위 10~44점, 폭 7점씩 5구간)

    @Test void allLowestAnswersScoreTenAndMapToStableTendency() {
        // 전부 최저 선택 → score = 1+1+2+1+3+2 = 10(최솟값) → 1구간(안정형)
        assertThat(SurveyTendencyEvaluator.evaluateInvestmentTendency(List.of(1, 1, 1, 1, 1, 1, 1, 1))).isEqualTo(1);
    }

    @Test void allHighestAnswersScoreFortyFourAndMapToAggressiveTendency() {
        // 전부 최고 선택 → score = 5+4+10+4+15+6 = 44(최댓값) → 5구간(공격투자형)
        assertThat(SurveyTendencyEvaluator.evaluateInvestmentTendency(List.of(4, 5, 4, 5, 4, 5, 3, 3))).isEqualTo(5);
    }

    @Test void midRangeScoresMapToMiddleTendencyBuckets() {
        // 전부 2 선택 → score = 2+2+4+2+6+4 = 20 → 2구간(안정추구형)
        assertThat(SurveyTendencyEvaluator.evaluateInvestmentTendency(List.of(2, 2, 2, 2, 2, 2, 2, 2))).isEqualTo(2);
        // 전부 3 선택(7·8번은 최대 3) → score = 3+3+6+3+9+6 = 30 → 3구간(위험중립형)
        assertThat(SurveyTendencyEvaluator.evaluateInvestmentTendency(List.of(3, 3, 3, 3, 3, 3, 3, 3))).isEqualTo(3);
        // Q2~Q5=4, Q6·Q7=3 → score = 4+4+8+4+9+6 = 35 → 4구간(적극투자형)
        assertThat(SurveyTendencyEvaluator.evaluateInvestmentTendency(List.of(1, 4, 4, 4, 4, 3, 3, 1))).isEqualTo(4);
    }

    // 자금성향은 1번 문항 답을 그대로 반환한다(선택지 순서 = 등급 순서로 1:1 매핑).
    @Test void returnsFirstAnswerAsFundTendencyDirectly() {
        for (int purpose = 1; purpose <= 4; purpose++) {
            assertThat(SurveyTendencyEvaluator.evaluateFundTendency(List.of(purpose, 1, 1, 1, 1, 1, 1, 1))).isEqualTo(purpose);
        }
    }
}
