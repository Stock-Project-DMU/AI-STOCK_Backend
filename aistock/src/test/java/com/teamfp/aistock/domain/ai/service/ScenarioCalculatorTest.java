package com.teamfp.aistock.domain.ai.service;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.teamfp.aistock.domain.ai.dto.ScenarioPointDto;
import com.teamfp.aistock.domain.ai.dto.ScenarioSetDto;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * feature/simulation 1차 PR — ScenarioCalculator 단위 테스트.
 *
 * LocalDate.now()에 의존하지 않도록 모든 테스트에서 startDate를 고정값(2026-01-15)으로
 * 넘긴다. ScenarioCalculator가 startDate를 매월 1일로 정규화하므로 month 0의 date는
 * 항상 2026-01-01이 된다.
 */
class ScenarioCalculatorTest {

    private static final long INVESTMENT_AMOUNT = 10_000_000L;
    private static final LocalDate START_DATE = LocalDate.of(2026, 1, 15);
    private static final LocalDate NORMALIZED_MONTH_0 = LocalDate.of(2026, 1, 1);

    @Test
    @DisplayName("clamp - best/worst 상한(월 +8%)을 넘는 값은 +8%로 잘린다")
    void clamp_wideUpperBound() {
        ScenarioSetDto result = ScenarioCalculator.calculate(
                INVESTMENT_AMOUNT, 0.5, 0.01, -0.05, Long.MAX_VALUE, 1, START_DATE);

        long expected = Math.round(INVESTMENT_AMOUNT * 1.08);
        assertThat(result.bestPoints().get(1).value()).isEqualTo(expected);
    }

    @Test
    @DisplayName("clamp - best/worst 하한(월 -8%)을 넘는 값은 -8%로 잘린다")
    void clamp_wideLowerBound() {
        ScenarioSetDto result = ScenarioCalculator.calculate(
                INVESTMENT_AMOUNT, 0.05, 0.0, -0.5, Long.MAX_VALUE, 1, START_DATE);

        long expected = Math.round(INVESTMENT_AMOUNT * 0.92);
        assertThat(result.worstPoints().get(1).value()).isEqualTo(expected);
    }

    @Test
    @DisplayName("clamp - base 상한(월 +2%)을 넘는 값은 +2%로 잘린다")
    void clamp_narrowUpperBound() {
        ScenarioSetDto result = ScenarioCalculator.calculate(
                INVESTMENT_AMOUNT, 0.07, 0.5, -0.03, Long.MAX_VALUE, 1, START_DATE);

        long expected = Math.round(INVESTMENT_AMOUNT * 1.02);
        assertThat(result.basePoints().get(1).value()).isEqualTo(expected);
    }

    @Test
    @DisplayName("clamp - base 하한(월 -2%)을 넘는 값은 -2%로 잘린다")
    void clamp_narrowLowerBound() {
        ScenarioSetDto result = ScenarioCalculator.calculate(
                INVESTMENT_AMOUNT, 0.07, -0.5, -0.06, Long.MAX_VALUE, 1, START_DATE);

        long expected = Math.round(INVESTMENT_AMOUNT * 0.98);
        assertThat(result.basePoints().get(1).value()).isEqualTo(expected);
    }

    @Test
    @DisplayName("정렬 재배정 - clamp 폭 차이로 원래 라벨의 순서가 뒤집혀도 best>=base>=worst가 보장된다")
    void clampThenReorder_guaranteesBestGreaterThanOrEqualBaseGreaterThanOrEqualWorst() {
        // best/base는 큰 폭으로 하락시켜 clamp되고(-0.08, -0.02), worst만 clamp 없이
        // 양의 값(0.07)을 그대로 유지한다. clamp 전 라벨 순서를 그대로 믿으면
        // best(-0.08) < base(-0.02) < worst(0.07)로 뒤집힌 채 나가게 되므로,
        // 재정렬이 실제로 동작하는지 검증하는 케이스다.
        ScenarioSetDto result = ScenarioCalculator.calculate(
                INVESTMENT_AMOUNT,
                -0.5,   // best 원본 라벨 → clamp(wide) → -0.08
                -0.5,   // base 원본 라벨 → clamp(narrow) → -0.02
                0.07,   // worst 원본 라벨 → clamp 없음 → 0.07
                Long.MAX_VALUE, 3, START_DATE);

        // 재배정 후: 실제로 가장 큰 값(0.07)이 best로, 가장 작은 값(-0.08)이 worst로 가야 한다.
        assertThat(result.bestPoints().get(1).value()).isEqualTo(Math.round(INVESTMENT_AMOUNT * 1.07));
        assertThat(result.basePoints().get(1).value()).isEqualTo(Math.round(INVESTMENT_AMOUNT * 0.98));
        assertThat(result.worstPoints().get(1).value()).isEqualTo(Math.round(INVESTMENT_AMOUNT * 0.92));

        // 모든 month에서 best >= base >= worst 불변식이 성립하는지 전 구간 검증
        for (int month = 0; month <= 3; month++) {
            long best = result.bestPoints().get(month).value();
            long base = result.basePoints().get(month).value();
            long worst = result.worstPoints().get(month).value();
            assertThat(best).isGreaterThanOrEqualTo(base);
            assertThat(base).isGreaterThanOrEqualTo(worst);
        }
    }

    @Test
    @DisplayName("reachDate - value가 targetAmount를 처음 넘는 달을 정확히 찾는다")
    void findReachDate_exactMonth() {
        // 세 시나리오 모두 월 2%로 고정(정렬 결과가 항상 동일하도록) — 1.02^4≈10,824,322,
        // 1.02^5≈11,040,808이라 targetAmount=11,000,000이면 5개월째에 처음 도달한다.
        ScenarioSetDto result = ScenarioCalculator.calculate(
                INVESTMENT_AMOUNT, 0.02, 0.02, 0.02, 11_000_000L, 12, START_DATE);

        LocalDate expectedReachDate = NORMALIZED_MONTH_0.plusMonths(5);
        assertThat(result.bestReachDate()).isEqualTo(expectedReachDate);
        assertThat(result.baseReachDate()).isEqualTo(expectedReachDate);
        assertThat(result.worstReachDate()).isEqualTo(expectedReachDate);
    }

    @Test
    @DisplayName("reachDate - targetMonths 안에 도달하지 못하면 null이다")
    void findReachDate_null_whenNeverReached() {
        ScenarioSetDto result = ScenarioCalculator.calculate(
                INVESTMENT_AMOUNT, -0.02, -0.02, -0.02, 50_000_000L, 12, START_DATE);

        assertThat(result.bestReachDate()).isNull();
        assertThat(result.baseReachDate()).isNull();
        assertThat(result.worstReachDate()).isNull();
    }

    @Test
    @DisplayName("reachDate - investmentAmount가 이미 targetAmount 이상이면 month 0(시작일)이 reachDate다")
    void findReachDate_month0_whenAlreadyReached() {
        ScenarioSetDto result = ScenarioCalculator.calculate(
                INVESTMENT_AMOUNT, 0.01, 0.0, -0.01, 5_000_000L, 12, START_DATE);

        assertThat(result.bestReachDate()).isEqualTo(NORMALIZED_MONTH_0);
        assertThat(result.baseReachDate()).isEqualTo(NORMALIZED_MONTH_0);
        assertThat(result.worstReachDate()).isEqualTo(NORMALIZED_MONTH_0);
    }

    @Test
    @DisplayName("targetMonths 경계값 1 - 곡선은 month 0, 1 두 포인트만 생성한다")
    void generateCurve_targetMonthsLowerBoundary() {
        ScenarioSetDto result = ScenarioCalculator.calculate(
                INVESTMENT_AMOUNT, 0.03, 0.01, -0.02, Long.MAX_VALUE, 1, START_DATE);

        List<ScenarioPointDto> bestPoints = result.bestPoints();
        assertThat(bestPoints).hasSize(2);
        assertThat(bestPoints.get(0).date()).isEqualTo(NORMALIZED_MONTH_0);
        assertThat(bestPoints.get(0).value()).isEqualTo(INVESTMENT_AMOUNT);
        assertThat(bestPoints.get(1).date()).isEqualTo(NORMALIZED_MONTH_0.plusMonths(1));
    }

    @Test
    @DisplayName("targetMonths 경계값 12 - 곡선은 month 0부터 12까지 13개 포인트를 생성한다")
    void generateCurve_targetMonthsUpperBoundary() {
        ScenarioSetDto result = ScenarioCalculator.calculate(
                INVESTMENT_AMOUNT, 0.03, 0.01, -0.02, Long.MAX_VALUE, 12, START_DATE);

        List<ScenarioPointDto> bestPoints = result.bestPoints();
        assertThat(bestPoints).hasSize(13);
        assertThat(bestPoints.get(12).date()).isEqualTo(NORMALIZED_MONTH_0.plusMonths(12));
    }
}
