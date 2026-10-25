package com.teamfp.aistock.domain.user.service;

import java.util.List;

/**
 * 설문 응답으로 투자성향(1~5)·자금성향(1~3)을 계산한다.
 * 답변 유효성 검증(문항 개수·선택지 범위)은 같은 트랜잭션에서 먼저 호출되는
 * {@link SurveyLevelEvaluator#evaluate}가 이미 수행하므로 여기서는 다시 검증하지 않는다
 * (UserService.saveSurvey()가 항상 SurveyLevelEvaluator.evaluate()를 먼저 호출한 뒤
 * 이 클래스를 호출하는 순서를 지켜야 한다).
 */
final class SurveyTendencyEvaluator {
    private SurveyTendencyEvaluator() {}

    // 투자성향(1~5) — 손실 감내(6번)를 가장 크게, 투자경험(4번)·지속기간(7번)을 그다음으로,
    // 나머지(연소득 2번·자산비중 3번·금융지식 5번)를 보조 지표로 가중합산한다.
    // 합계 범위는 10~44점(각 문항 최저 선택 시 10, 최고 선택 시 44)이며, 폭 7점씩 5구간으로
    // 나눠 등급을 매긴다.
    //
    // 4번 문항(투자경험)만 "6 - 답"으로 방향을 뒤집는다 — 이 문항은 체크박스 중복 선택이고
    // 선택지가 1번(선물·옵션, 최고위험)부터 5번(주식형 펀드)까지 위험한 순서로 놓여 있어서, 다른
    // 문항들(번호가 클수록 공격적)과 반대로 번호가 작을수록 공격적인 답이다. 답에는 고른 것 중
    // 가장 위험한 경험(가장 작은 번호)이 들어온다. 6번(없음 — 투자 경험 없음)은 0점으로 가장 낮다
    // (2026-10-13 프론트 확인 후 추가, 그래서 합계 최저점은 8점이고 1구간으로 묶인다). 이전 코드는
    // 이 문항도 그냥 답 번호를 그대로 곱해서 공격형 점수를 반대로 줬었다(우혁 2026-09-29
    // 재반려 사유 3-2 지적).
    static final int NO_EXPERIENCE_ANSWER = 6;

    static int evaluateInvestmentTendency(List<Integer> answers) {
        int score = answers.get(1) + answers.get(2) + 2 * (NO_EXPERIENCE_ANSWER - answers.get(3))
                + answers.get(4) + 3 * answers.get(5) + 2 * answers.get(6);
        return Math.max(1, Math.min(5, (score - 10) / 7 + 1));
    }

    // 자금성향(1~3) — 1번 문항(투자 목적) 답을 그대로 사용한다. 실제 프론트
    // (surveyQuestions.ts) 선택지 순서(1:자산증식→수익추구형, 2:생활비→자유소비형,
    // 3:채무상환→목표달성형)와 1:1로 맞춰뒀으므로 별도 매핑이 필요 없다. 예전엔 여기 없는
    // "목돈 모으기·저축"을 4번째로 가정해 선택지 수가 어긋났었다(2026-09-28 리뷰 반려 사유 3번
    // — SurveyLevelEvaluator.evaluate() 참고).
    static int evaluateFundTendency(List<Integer> answers) {
        return answers.get(0);
    }
}
