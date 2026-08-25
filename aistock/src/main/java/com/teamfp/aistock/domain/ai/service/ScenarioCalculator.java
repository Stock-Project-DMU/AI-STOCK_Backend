package com.teamfp.aistock.domain.ai.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.teamfp.aistock.domain.ai.dto.ScenarioPointDto;
import com.teamfp.aistock.domain.ai.dto.ScenarioSetDto;

/**
 * 목표 도달 시뮬레이션의 시나리오 곡선 계산 엔진.
 *
 * Gemini는 시나리오별 월 복리 성장률(스칼라 하나) + 근거만 반환하고, 실제
 * date+value 곡선과 Best≥Base≥Worst 정렬 보장은 이 클래스가 순수 계산으로
 * 만든다 — 산술 오류 없는 정확성과, Gemini 출력 토큰을 줄여 지연시간을
 * 낮추기 위한 설계다.
 *
 * 상태가 없는 순수 함수라 Spring 빈으로 두지 않는다. LocalDate.now()도 내부에서
 * 직접 호출하지 않고 startDate로 외부 주입받는다 — 그래야 단위테스트가 실행
 * 시각에 의존하지 않는 결정론적 결과를 검증할 수 있다.
 */
public final class ScenarioCalculator {

    // 월 ±8% 상한: 12개월 복리 시 약 +152%/-63%에 해당한다.
    // 월 ±2% 상한: 12개월 복리 시 약 +27%/-21%에 해당한다.
    // clamp가 없으면(예: 월 20%) 12개월 복리로 연 +792% 같은 비현실적 수치가
    // 모의투자 학습 플랫폼 사용자에게 그대로 노출될 수 있어, 보수적 방어값으로 둔다.
    private static final double MAX_MONTHLY_GROWTH_RATE_WIDE = 0.08;
    private static final double MAX_MONTHLY_GROWTH_RATE_NARROW = 0.02;

    private ScenarioCalculator() {
    }

    /**
     * @param startDate 곡선의 month 0 기준일. LocalDate.now()를 이 메서드 내부에서
     *                   직접 호출하지 않기 위해 호출 측이 넘겨준다.
     */
    public static ScenarioSetDto calculate(
            long investmentAmount,
            double bestMonthlyGrowthRate,
            double baseMonthlyGrowthRate,
            double worstMonthlyGrowthRate,
            long targetAmount,
            int targetMonths,
            LocalDate startDate
    ) {
        double clampedBest = clamp(bestMonthlyGrowthRate, MAX_MONTHLY_GROWTH_RATE_WIDE);
        double clampedBase = clamp(baseMonthlyGrowthRate, MAX_MONTHLY_GROWTH_RATE_NARROW);
        double clampedWorst = clamp(worstMonthlyGrowthRate, MAX_MONTHLY_GROWTH_RATE_WIDE);

        // best/worst의 clamp 폭이 base보다 넓기 때문에, clamp 후에는 원래 라벨을
        // 신뢰할 수 없다(예: worst로 받은 값이 clamp 후 base보다 커질 수 있음).
        // 그래서 라벨을 버리고 세 값을 내림차순 정렬해 다시 배정한다 — 이렇게 하면
        // best ≥ base ≥ worst가 항상 성립한다.
        double[] sortedAscending = {clampedBest, clampedBase, clampedWorst};
        java.util.Arrays.sort(sortedAscending);
        double finalWorst = sortedAscending[0];
        double finalBase = sortedAscending[1];
        double finalBest = sortedAscending[2];

        LocalDate normalizedStart = startDate.withDayOfMonth(1);

        List<ScenarioPointDto> bestPoints = generateCurve(investmentAmount, finalBest, targetMonths, normalizedStart);
        List<ScenarioPointDto> basePoints = generateCurve(investmentAmount, finalBase, targetMonths, normalizedStart);
        List<ScenarioPointDto> worstPoints = generateCurve(investmentAmount, finalWorst, targetMonths, normalizedStart);

        return new ScenarioSetDto(
                bestPoints,
                basePoints,
                worstPoints,
                findReachDate(bestPoints, targetAmount),
                findReachDate(basePoints, targetAmount),
                findReachDate(worstPoints, targetAmount)
        );
    }

    private static double clamp(double monthlyGrowthRate, double maxAbsRate) {
        return Math.max(-maxAbsRate, Math.min(maxAbsRate, monthlyGrowthRate));
    }

    private static List<ScenarioPointDto> generateCurve(long investmentAmount, double monthlyGrowthRate,
                                                          int targetMonths, LocalDate normalizedStart) {
        List<ScenarioPointDto> points = new ArrayList<>();
        for (int month = 0; month <= targetMonths; month++) {
            long value = Math.round(investmentAmount * Math.pow(1 + monthlyGrowthRate, month));
            points.add(new ScenarioPointDto(normalizedStart.plusMonths(month), value));
        }
        return points;
    }

    /**
     * value >= targetAmount를 처음 만족하는 포인트의 date를 반환한다.
     * investmentAmount(= month 0의 value)가 이미 targetAmount 이상이면 month 0에서
     * 바로 만족하므로 별도 분기 없이 이 루프만으로 처리된다. 끝까지 못 만족하면 null.
     */
    private static LocalDate findReachDate(List<ScenarioPointDto> points, long targetAmount) {
        for (ScenarioPointDto point : points) {
            if (point.value() >= targetAmount) {
                return point.date();
            }
        }
        return null;
    }
}
