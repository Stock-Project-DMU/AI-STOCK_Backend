package com.teamfp.aistock.domain.user.service;

import java.util.List;
import org.junit.jupiter.api.Test;
import com.teamfp.aistock.domain.user.entity.InvestmentLevel;
import com.teamfp.aistock.global.exception.CustomException;
import static org.assertj.core.api.Assertions.*;

class SurveyLevelEvaluatorTest {
    @Test void evaluatesAllKnowledgeAndExperienceCombinations() {
        for (int knowledge = 1; knowledge <= 4; knowledge++) {
            for (int years = 1; years <= 3; years++) {
                var expected = knowledge == 4 && years == 3 ? InvestmentLevel.EXPERT
                        : knowledge >= 3 && years >= 2 ? InvestmentLevel.INTERMEDIATE : InvestmentLevel.BEGINNER;
                assertThat(SurveyLevelEvaluator.evaluate(List.of(1, 1, 1, 1, knowledge, 1, 1, years))).isEqualTo(expected);
            }
        }
    }
    @Test void rejectsIncompleteAndOutOfRangeAnswers() {
        assertThatThrownBy(() -> SurveyLevelEvaluator.evaluate(List.of(1, 2, 3))).isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> SurveyLevelEvaluator.evaluate(List.of(1, 1, 1, 1, 5, 1, 1, 3))).isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> SurveyLevelEvaluator.evaluate(null)).isInstanceOf(CustomException.class);
    }
}
