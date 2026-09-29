package com.teamfp.aistock.domain.user.service;

import java.util.List;
import com.teamfp.aistock.domain.user.entity.InvestmentLevel;
import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;

final class SurveyLevelEvaluator {
    private SurveyLevelEvaluator() {}

    static InvestmentLevel evaluate(List<Integer> answers) {
        // 1번 문항(투자 목적)은 FRONTEND_API_IMPLEMENTATION.md가 문서화한 실제 프론트 매핑
        // 기준 3개 선택지(자산증식→수익추구형 / 생활비→자유소비형 / 채무상환→목표달성형)다.
        // 한때 "목돈 모으기·저축" 선택지를 추가해 4개로 늘렸었지만 프론트가 그 선택지를
        // 갖고 있지 않아 선택지 수 불일치(2026-09-28 리뷰 반려 사유 3번)를 냈으므로 되돌린다
        // (SurveyTendencyEvaluator.evaluateFundTendency()가 이 문항 답을 그대로 자금성향 값으로
        // 쓰므로 선택지 개수는 프론트와 반드시 1:1로 맞아야 한다).
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
