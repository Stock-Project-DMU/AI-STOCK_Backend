package com.teamfp.aistock.domain.user.service;

import java.util.List;
import com.teamfp.aistock.domain.user.entity.InvestmentLevel;
import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;

final class SurveyLevelEvaluator {
    private SurveyLevelEvaluator() {}

    static InvestmentLevel evaluate(List<Integer> answers) {
        int[] optionCounts = {3, 5, 4, 5, 4, 5, 3, 3};
        if (answers == null || answers.size() != optionCounts.length) {
            throw new CustomException(ErrorCode.INVALID_INPUT);
        }
        for (int i = 0; i < optionCounts.length; i++) {
            Integer answer = answers.get(i);
            if (answer == null || answer < 1 || answer > optionCounts[i]) {
                throw new CustomException(ErrorCode.INVALID_INPUT);
            }
        }
        int knowledge = answers.get(4);
        int experience = answers.get(7);
        if (knowledge == 4 && experience == 3) return InvestmentLevel.EXPERT;
        if (knowledge >= 3 && experience >= 2) return InvestmentLevel.INTERMEDIATE;
        return InvestmentLevel.BEGINNER;
    }
}
