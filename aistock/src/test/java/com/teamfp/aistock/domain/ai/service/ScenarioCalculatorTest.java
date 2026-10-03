package com.teamfp.aistock.domain.ai.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.teamfp.aistock.domain.ai.dto.PortfolioAllocationDto;
import com.teamfp.aistock.domain.ai.dto.ScenarioPointDto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * feature/goal-simulation-v2 — ScenarioCalculator 단위 테스트.
 *
 * LocalDate.now()에 의존하지 않도록 startDate를 고정값(2026-01-15)으로 넘긴다.
 * ScenarioCalculator가 startDate를 매월 1일로 정규화하므로 month 0의 date는 항상 2026-01-01이다.
 */
class ScenarioCalculatorTest {

    private static final LocalDate START_DATE = LocalDate.of(2026, 1, 15);

    @Test
    @DisplayName("보수적 성장률 - 월 수익률 기하평균이 양수면 30% 할인한다")
    void conservativeMonthlyRate_positiveAverageIsDiscounted() {
        // 최신순 종가: 10404 ← 10200 ← 10000 → 월 수익률 +2%, +2% → 기하평균 (10404/10000)^(1/2)-1 = 2% → 0.7배 = 1.4%
        // (월 5% 상한 아래 값으로 할인만 검증한다)
        double rate = ScenarioCalculator.conservativeMonthlyRate(List.of(10404L, 10200L, 10000L));

        assertThat(rate).isCloseTo(0.014, within(1e-9));
    }

    @Test
    @DisplayName("보수적 성장률 - 산술평균이 아니라 기하평균(복리 기준)이다: +100% 뒤 -50%면 0%")
    void conservativeMonthlyRate_usesGeometricMean() {
        // 최신순 종가: 100 ← 200 ← 100 → 월 수익률 +100%, -50% → 산술평균 25%지만 실제 복리 수익률은 0%
        double rate = ScenarioCalculator.conservativeMonthlyRate(List.of(100L, 200L, 100L));

        assertThat(rate).isCloseTo(0.0, within(1e-9));
    }

    @Test
    @DisplayName("보수적 성장률 - 변동성 손실도 반영된다: +50% 뒤 -50%면 음수(할인 없이 그대로)")
    void conservativeMonthlyRate_volatilityDragIsNegative() {
        // 최신순 종가: 75 ← 150 ← 100 → 산술평균 0%, 기하평균 (75/100)^(1/2)-1 ≈ -13.40%
        double rate = ScenarioCalculator.conservativeMonthlyRate(List.of(75L, 150L, 100L));

        assertThat(rate).isCloseTo(Math.sqrt(0.75) - 1, within(1e-9));
    }

    @Test
    @DisplayName("보수적 성장률 - 할인 뒤에도 월 5%를 넘으면 5%로 자른다")
    void conservativeMonthlyRate_isCappedAtFivePercent() {
        // 최신순 종가: 200 ← 100 → +100% → 0.7배 = 70% → 상한 5%
        double rate = ScenarioCalculator.conservativeMonthlyRate(List.of(200L, 100L));

        assertThat(rate).isEqualTo(ScenarioCalculator.MAX_MONTHLY_GROWTH_RATE);
        assertThat(ScenarioCalculator.MAX_MONTHLY_GROWTH_RATE).isEqualTo(0.05);
    }

    @Test
    @DisplayName("보수적 성장률 - 할인 뒤 5% 미만이면 상한을 적용하지 않는다")
    void conservativeMonthlyRate_belowCapIsUntouched() {
        // 최신순 종가: 107 ← 100 → +7% → 0.7배 = 4.9%
        double rate = ScenarioCalculator.conservativeMonthlyRate(List.of(107L, 100L));

        assertThat(rate).isCloseTo(0.049, within(1e-9));
    }

    @Test
    @DisplayName("보수적 성장률 - 평균이 음수면 할인하지 않고 그대로 쓴다(손실을 줄이지 않음)")
    void conservativeMonthlyRate_negativeAverageIsKept() {
        // 최신순 종가: 90 ← 100 → 월 수익률 -10%
        double rate = ScenarioCalculator.conservativeMonthlyRate(List.of(90L, 100L));

        assertThat(rate).isCloseTo(-0.10, within(1e-9));
    }

    @Test
    @DisplayName("보수적 성장률 - 최근 36개월 수익률만 쓰고 그보다 오래된 봉은 무시한다")
    void conservativeMonthlyRate_usesOnly36Months() {
        // 최근 36개월은 보합(100), 그보다 오래된 37번째 수익률은 +100%(50 → 100)
        List<Long> closes = new ArrayList<>();
        for (int i = 0; i < 37; i++) {
            closes.add(100L);
        }
        closes.add(50L);

        assertThat(ScenarioCalculator.conservativeMonthlyRate(closes)).isEqualTo(0.0);
    }

    @Test
    @DisplayName("보수적 성장률 - 유효한 종가가 1개 이하(null/0 제외)면 이력 없음으로 0을 반환한다")
    void conservativeMonthlyRate_noHistory() {
        assertThat(ScenarioCalculator.conservativeMonthlyRate(List.of())).isEqualTo(0.0);
        assertThat(ScenarioCalculator.conservativeMonthlyRate(List.of(100L))).isEqualTo(0.0);
        assertThat(ScenarioCalculator.conservativeMonthlyRate(Arrays.asList(null, 0L, 100L))).isEqualTo(0.0);
    }

    @Test
    @DisplayName("월 수익률 개수 - 유효한 종가 수 - 1")
    void countMonthlyReturns() {
        assertThat(ScenarioCalculator.countMonthlyReturns(List.of(3L, 2L, 1L))).isEqualTo(2);
        assertThat(ScenarioCalculator.countMonthlyReturns(List.of())).isZero();
    }

    @Test
    @DisplayName("포트폴리오 월 성장률 - 비중 가중평균이며 예수금(목록에 없는 비중)은 0%로 계산된다")
    void portfolioMonthlyRate() {
        List<PortfolioAllocationDto> allocations = List.of(
                new PortfolioAllocationDto("005930", "삼성전자", 40, 0.02),
                new PortfolioAllocationDto("000660", "SK하이닉스", 20, 0.01));

        // 0.4*0.02 + 0.2*0.01 = 0.01 (나머지 40%는 예수금 0%)
        assertThat(ScenarioCalculator.portfolioMonthlyRate(allocations)).isCloseTo(0.01, within(1e-12));
    }

    @Test
    @DisplayName("곡선 - 매달 직전 금액 × (1+성장률) + 월 납입, month 0은 시작 금액·매월 1일")
    void projectCurve() {
        List<ScenarioPointDto> points = ScenarioCalculator.projectCurve(1_000_000, 100_000, 0.01, 2, START_DATE);

        assertThat(points).hasSize(3);
        assertThat(points.get(0)).isEqualTo(new ScenarioPointDto(LocalDate.of(2026, 1, 1), 1_000_000));
        assertThat(points.get(1)).isEqualTo(new ScenarioPointDto(LocalDate.of(2026, 2, 1), 1_110_000));
        assertThat(points.get(2)).isEqualTo(new ScenarioPointDto(LocalDate.of(2026, 3, 1), 1_221_100));
    }

    @Test
    @DisplayName("곡선 - 손실이 커도 평가금액은 0 아래로 내려가지 않는다")
    void projectCurve_neverNegative() {
        List<ScenarioPointDto> points = ScenarioCalculator.projectCurve(100, 0, -2.0, 3, START_DATE);

        assertThat(points).extracting(ScenarioPointDto::value).containsOnly(100L, 0L);
    }

    @Test
    @DisplayName("도달 개월 - 처음 목표 이상이 되는 달, 시작부터 목표 이상이면 0, 끝까지 못 미치면 null")
    void findReachMonths() {
        List<ScenarioPointDto> points = ScenarioCalculator.projectCurve(1_000_000, 100_000, 0.0, 10, START_DATE);

        assertThat(ScenarioCalculator.findReachMonths(points, 1_250_000)).isEqualTo(3);
        assertThat(ScenarioCalculator.findReachMonths(points, 500_000)).isZero();
        assertThat(ScenarioCalculator.findReachMonths(points, 999_999_999)).isNull();
    }

    @Test
    @DisplayName("표시 기간 - 기한과 두 도달 시점 중 가장 늦은 값, 미도달이 있으면 30년, 최소 1개월")
    void displayMonths() {
        assertThat(ScenarioCalculator.displayMonths(null, 24, 18)).isEqualTo(24);
        assertThat(ScenarioCalculator.displayMonths(36, 24, 18)).isEqualTo(36);
        assertThat(ScenarioCalculator.displayMonths(null, null, 18)).isEqualTo(360);
        assertThat(ScenarioCalculator.displayMonths(null, 0, 0)).isEqualTo(1);
    }
}
