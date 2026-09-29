package com.teamfp.aistock.domain.user.service;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SurveyTendencyEvaluatorTest {

    // 투자성향 가중합산: score = Q2+Q3+2*(6-Q4)+Q5+3*Q6+2*Q7 (범위 10~44점, 폭 7점씩 5구간).
    // Q4(투자경험)만 "6-Q4"로 방향이 뒤집힌다 — 프론트 선택지가 1번(최고위험)부터
    // 5번(무경험)까지 내림차순이라, 번호가 작을수록 공격적인 답이기 때문이다
    // (우혁 2026-09-29 재반려 사유 3-2 대응, SurveyTendencyEvaluator 참고).

    @Test void lowestPossibleScoreRequiresLeastAggressiveQ4AnswerAndMapsToStableTendency() {
        // 진짜 최솟값은 Q4가 5(가장 소극적, 6-5=1로 최소 기여)일 때 나온다.
        // score = Q2(1)+Q3(1)+2*(6-5=1)+Q5(1)+3*Q6(1)+2*Q7(1) = 1+1+2+1+3+2 = 10(최솟값) → 1구간(안정형)
        assertThat(SurveyTendencyEvaluator.evaluateInvestmentTendency(List.of(1, 1, 1, 5, 1, 1, 1, 1))).isEqualTo(1);
    }

    @Test void highestPossibleScoreRequiresMostAggressiveQ4AnswerAndMapsToAggressiveTendency() {
        // 진짜 최댓값은 Q4가 1(가장 공격적, 6-1=5로 최대 기여)일 때 나온다.
        // score = Q2(5)+Q3(4)+2*(6-1=5)+Q5(4)+3*Q6(5)+2*Q7(3) = 5+4+10+4+15+6 = 44(최댓값) → 5구간(공격투자형)
        assertThat(SurveyTendencyEvaluator.evaluateInvestmentTendency(List.of(1, 5, 4, 1, 4, 5, 3, 3))).isEqualTo(5);
    }

    @Test void q4DirectionIsReversedComparedToOtherQuestions() {
        // 나머지 문항은 전부 최저(1)로 고정하고 Q4만 바꿔본다 — Q4=1(최고위험)이
        // Q4=5(무경험)보다 더 높은 점수를 내야 방향이 제대로 뒤집힌 것이다.
        int scoreWhenQ4IsMostAggressive = 1 + 1 + 2 * (6 - 1) + 1 + 3 + 2; // = 18
        int scoreWhenQ4IsLeastAggressive = 1 + 1 + 2 * (6 - 5) + 1 + 3 + 2; // = 10
        assertThat(SurveyTendencyEvaluator.evaluateInvestmentTendency(List.of(1, 1, 1, 1, 1, 1, 1, 1)))
                .isEqualTo(Math.min(5, (scoreWhenQ4IsMostAggressive - 10) / 7 + 1));
        assertThat(SurveyTendencyEvaluator.evaluateInvestmentTendency(List.of(1, 1, 1, 5, 1, 1, 1, 1)))
                .isEqualTo(Math.min(5, (scoreWhenQ4IsLeastAggressive - 10) / 7 + 1));
        assertThat(scoreWhenQ4IsMostAggressive).isGreaterThan(scoreWhenQ4IsLeastAggressive);
    }

    @Test void midRangeScoresMapToMiddleTendencyBuckets() {
        // 전부 2 선택 → Q4=2는 6-2=4로 기여 → score = 2+2+8+2+6+4 = 24 → 3구간(위험중립형)
        assertThat(SurveyTendencyEvaluator.evaluateInvestmentTendency(List.of(2, 2, 2, 2, 2, 2, 2, 2))).isEqualTo(3);
        // 전부 3 선택(7·8번은 최대 3) → Q4=3은 6-3=3으로 반전 전과 동일(대칭점) →
        // score = 3+3+6+3+9+6 = 30 → 3구간(위험중립형)
        assertThat(SurveyTendencyEvaluator.evaluateInvestmentTendency(List.of(3, 3, 3, 3, 3, 3, 3, 3))).isEqualTo(3);
        // Q2~Q5=4, Q6·Q7=3, Q4=4는 6-4=2로 기여 → score = 4+4+4+4+9+6 = 31 → 4구간(적극투자형)
        assertThat(SurveyTendencyEvaluator.evaluateInvestmentTendency(List.of(1, 4, 4, 4, 4, 3, 3, 1))).isEqualTo(4);
    }

    // 자금성향은 1번 문항 답을 그대로 반환한다(프론트 3개 선택지 순서 = 등급 순서로 1:1 매핑,
    // FRONTEND_API_IMPLEMENTATION.md 참고).
    @Test void returnsFirstAnswerAsFundTendencyDirectly() {
        for (int purpose = 1; purpose <= 3; purpose++) {
            assertThat(SurveyTendencyEvaluator.evaluateFundTendency(List.of(purpose, 1, 1, 1, 1, 1, 1, 1))).isEqualTo(purpose);
        }
    }
}
