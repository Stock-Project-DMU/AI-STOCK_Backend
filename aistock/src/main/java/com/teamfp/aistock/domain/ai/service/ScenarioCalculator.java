package com.teamfp.aistock.domain.ai.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.teamfp.aistock.domain.ai.dto.PortfolioAllocationDto;
import com.teamfp.aistock.domain.ai.dto.ScenarioPointDto;

/**
 * 목표 도달 시뮬레이션의 계산 엔진(feature/goal-simulation-v2, 2026-10-01 재작성).
 *
 * 성장률은 Gemini가 추정하지 않고 실제 과거 시세로 서버가 계산한다 — 같은 입력이면 항상 같은
 * 결과가 나오고(Gemini 추정은 호출마다 값이 흔들림), "최근 3년 평균에서 30% 할인"처럼 근거를
 * 그대로 설명할 수 있다. 왼쪽(현재 보유)·오른쪽(리밸런싱) 차트 모두 이 클래스의 같은 규칙으로
 * 계산해야 두 결과를 공정하게 비교할 수 있다.
 *
 * 상태가 없는 순수 함수라 Spring 빈으로 두지 않는다. LocalDate.now()도 내부에서 직접
 * 호출하지 않고 startDate로 외부 주입받는다 — 단위테스트가 실행 시각에 의존하지 않게 하기 위함.
 */
public final class ScenarioCalculator {

    // 최대 계산 기간 30년. 이 안에 목표에 못 미치면 "현재 조건으로는 도달이 어렵습니다"로 안내한다.
    public static final int MAX_PROJECTION_MONTHS = 360;

    // 보수적 성장률을 낼 때 보는 과거 기간(월). 월봉 37개를 받으면 월 수익률 36개가 나온다.
    public static final int HISTORY_MONTHS = 36;

    // 리밸런싱 추천 종목은 월 수익률이 최소 12개(1년치) 있어야 쓴다 — 상장한 지 얼마 안 된 종목은
    // 몇 달짜리 평균이 3년 평균과 같은 무게로 비교되면 결과가 크게 왜곡된다.
    public static final int MIN_HISTORY_MONTHS_FOR_REBALANCE = 12;

    // 과거 평균 수익률에 곱하는 할인 계수(30% 할인).
    private static final double CONSERVATIVE_DISCOUNT_FACTOR = 0.7;

    // 종목별 월 성장률 상한(5%, 연 약 80%). 최근 3년 급등주의 과거 수익률이 30년 곡선에 그대로 이어지면
    // 결과가 비현실적으로 부풀려져(최종 테스트에서 SK하이닉스 월 7.22%) 할인 뒤에 한 번 더 자른다(2026-10-03).
    public static final double MAX_MONTHLY_GROWTH_RATE = 0.05;

    private ScenarioCalculator() {
    }

    /**
     * 월봉 종가 목록(최신순)으로 월 수익률 개수를 센다. 종가가 없거나 0 이하인 봉은 건너뛴다.
     */
    public static int countMonthlyReturns(List<Long> monthlyClosesNewestFirst) {
        return Math.max(0, validCloses(monthlyClosesNewestFirst).size() - 1);
    }

    /**
     * 보수적 월 성장률 = 최근 월 수익률(최대 {@value #HISTORY_MONTHS}개)의 기하평균(복리 기준)에 30% 할인,
     * 그리고 월 {@value #MAX_MONTHLY_GROWTH_RATE} 상한.
     *
     * 기하평균은 (가장 최근 종가 ÷ N개월 전 종가)^(1/N) − 1 이다. 산술평균은 변동성이 클수록 실제 복리
     * 수익률보다 크게 나와(+50% 뒤 −50%면 산술평균 0%지만 실제로는 −25%) 기하평균으로 바꿨다(2026-10-03).
     *
     * 할인은 "덜 낙관적으로" 만드는 방향으로만 적용한다 — 평균이 양수면 0.7배로 줄이고, 평균이
     * 0 이하면 그대로 둔다. 음수에 0.7을 곱하면 손실이 오히려 줄어들어(-1% → -0.7%) 보수적이라는
     * 취지와 반대가 되기 때문이다. 월 수익률이 하나도 없으면(이력 없음) 0을 반환한다.
     *
     * @param monthlyClosesNewestFirst 월봉 종가 목록, 가장 최근 달이 0번째
     */
    public static double conservativeMonthlyRate(List<Long> monthlyClosesNewestFirst) {
        List<Long> closes = validCloses(monthlyClosesNewestFirst);
        int returnCount = Math.min(HISTORY_MONTHS, closes.size() - 1);
        if (returnCount <= 0) {
            return 0.0;
        }
        double geometricMean = Math.pow((double) closes.get(0) / closes.get(returnCount), 1.0 / returnCount) - 1;
        double discounted = geometricMean > 0 ? geometricMean * CONSERVATIVE_DISCOUNT_FACTOR : geometricMean;
        return Math.min(MAX_MONTHLY_GROWTH_RATE, discounted);
    }

    /**
     * 포트폴리오 월 성장률 = 종목별 월 성장률을 비중(%)으로 가중평균. 예수금(현금) 비중은
     * allocations에 들어있지 않으므로 자연히 0%로 계산된다.
     */
    public static double portfolioMonthlyRate(List<PortfolioAllocationDto> allocations) {
        double rate = 0;
        for (PortfolioAllocationDto allocation : allocations) {
            rate += allocation.weight() / 100.0 * allocation.monthlyGrowthRate();
        }
        return rate;
    }

    /**
     * month 0(이번 달 1일, 값 = startAmount)부터 months개월 뒤까지 월별 평가금액 곡선을 만든다.
     * 매달 "직전 금액 × (1 + 월 성장률) + 월 추가 납입" 순서로 굴린다(납입은 그 달 말에 들어온다고 가정).
     * 금액이 음수로 내려가지는 않게 0에서 멈춘다.
     */
    public static List<ScenarioPointDto> projectCurve(long startAmount, long monthlyContribution, double monthlyRate,
                                                      int months, LocalDate startDate) {
        LocalDate normalizedStart = startDate.withDayOfMonth(1);
        List<ScenarioPointDto> points = new ArrayList<>(months + 1);
        double value = startAmount;
        points.add(new ScenarioPointDto(normalizedStart, Math.round(value)));
        for (int month = 1; month <= months; month++) {
            value = Math.max(0, value * (1 + monthlyRate) + monthlyContribution);
            points.add(new ScenarioPointDto(normalizedStart.plusMonths(month), Math.round(value)));
        }
        return points;
    }

    /**
     * value >= targetAmount를 처음 만족하는 포인트의 개월 수(인덱스). 시작 금액이 이미 목표
     * 이상이면 0, 곡선 끝까지 못 미치면 null.
     */
    public static Integer findReachMonths(List<ScenarioPointDto> points, long targetAmount) {
        for (int month = 0; month < points.size(); month++) {
            if (points.get(month).value() >= targetAmount) {
                return month;
            }
        }
        return null;
    }

    /**
     * 두 차트를 같은 길이로 잘라 보여줄 기간. 기한이 있으면 기한까지는 항상 보여주고, 두 포트폴리오
     * 중 늦게 도달하는 쪽의 도달 시점까지 보여준다. 어느 한쪽이라도 30년 안에 못 미치면 30년 전체.
     * 최소 1개월.
     */
    public static int displayMonths(Integer periodMonths, Integer currentReachMonths, Integer rebalancedReachMonths) {
        int currentEnd = currentReachMonths != null ? currentReachMonths : MAX_PROJECTION_MONTHS;
        int rebalancedEnd = rebalancedReachMonths != null ? rebalancedReachMonths : MAX_PROJECTION_MONTHS;
        int period = periodMonths != null ? periodMonths : 0;
        return Math.max(1, Math.min(MAX_PROJECTION_MONTHS, Math.max(period, Math.max(currentEnd, rebalancedEnd))));
    }

    private static List<Long> validCloses(List<Long> closes) {
        return closes.stream().filter(Objects::nonNull).filter(close -> close > 0).toList();
    }
}
