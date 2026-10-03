package com.teamfp.aistock.domain.ai.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.teamfp.aistock.domain.ai.dto.PortfolioAllocationDto;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * feature/goal-simulation-v2 — Gemini 리밸런싱 추천 서버 검증 규칙 단위 테스트.
 */
class RebalancePlanValidatorTest {

    private static final Set<String> ALLOWED = Set.of("005930", "000660", "035420", "035720", "005380",
            "051910", "006400", "068270", "105560", "055550", "012330", "028260");

    private static PortfolioAllocationDto item(String stockCode, double weight) {
        return new PortfolioAllocationDto(stockCode, null, weight, 0);
    }

    @Test
    @DisplayName("규칙을 지킨 추천은 그대로 통과하고 예수금 + 종목 비중 합은 100%다")
    void valid() {
        RebalancePlanValidator.Result result = RebalancePlanValidator.validate(20,
                List.of(item("005930", 40), item("000660", 25), item("035420", 15)), ALLOWED);

        assertThat(result.valid()).isTrue();
        assertThat(result.cashWeight()).isEqualTo(20.0);
        assertThat(result.allocations()).extracting(PortfolioAllocationDto::weight).containsExactly(40.0, 25.0, 15.0);
    }

    @Test
    @DisplayName("비중 합이 100이 아니면 비례 정규화한다")
    void normalizes() {
        // 합 200 → 절반으로
        RebalancePlanValidator.Result result = RebalancePlanValidator.validate(40,
                List.of(item("005930", 80), item("000660", 80)), ALLOWED);

        assertThat(result.valid()).isTrue();
        assertThat(result.cashWeight()).isEqualTo(20.0);
        assertThat(result.allocations()).extracting(PortfolioAllocationDto::weight).containsExactly(40.0, 40.0);
    }

    @Test
    @DisplayName("후보 목록 밖 종목·비중 0 이하는 버리고, 같은 종목은 비중을 합친다")
    void dropsUnknownAndMergesDuplicates() {
        RebalancePlanValidator.Result result = RebalancePlanValidator.validate(30,
                List.of(item("999999", 30), item("005930", 20), item("005930", 15), item("000660", 35), item("035420", 0)),
                ALLOWED);

        // 999999 제거 → 현금 30 + 005930 35 + 000660 35 = 100
        assertThat(result.valid()).isTrue();
        assertThat(result.allocations()).extracting(PortfolioAllocationDto::stockCode).containsExactly("005930", "000660");
        assertThat(result.allocations()).extracting(PortfolioAllocationDto::weight).containsExactly(35.0, 35.0);
    }

    @Test
    @DisplayName("주식 종목이 하나도 없으면 실패")
    void failsWithoutStocks() {
        RebalancePlanValidator.Result result = RebalancePlanValidator.validate(100, List.of(item("999999", 10)), ALLOWED);

        assertThat(result.valid()).isFalse();
        assertThat(result.violation()).contains("1개 이상");
    }

    @Test
    @DisplayName("주식 종목이 10개를 넘으면 실패")
    void failsWhenTooManyStocks() {
        List<PortfolioAllocationDto> items = new ArrayList<>();
        ALLOWED.stream().sorted().limit(11).forEach(code -> items.add(item(code, 9)));

        RebalancePlanValidator.Result result = RebalancePlanValidator.validate(1, items, ALLOWED);

        assertThat(result.valid()).isFalse();
        assertThat(result.violation()).contains("최대 10개");
    }

    @Test
    @DisplayName("정규화 후 종목 비중이 5% 미만이거나 40% 초과면 실패")
    void failsWhenWeightOutOfRange() {
        RebalancePlanValidator.Result tooBig = RebalancePlanValidator.validate(0,
                List.of(item("005930", 60), item("000660", 40)), ALLOWED);
        RebalancePlanValidator.Result tooSmall = RebalancePlanValidator.validate(57,
                List.of(item("005930", 40), item("000660", 3)), ALLOWED);

        assertThat(tooBig.valid()).isFalse();
        assertThat(tooBig.violation()).contains("005930");
        assertThat(tooSmall.valid()).isFalse();
        assertThat(tooSmall.violation()).contains("000660");
    }

    @Test
    @DisplayName("예수금 비중은 0~100으로 자른 뒤 정규화한다")
    void clampsCashWeight() {
        RebalancePlanValidator.Result result = RebalancePlanValidator.validate(-50,
                List.of(item("005930", 30), item("000660", 30), item("035420", 40)), ALLOWED);

        assertThat(result.valid()).isTrue();
        assertThat(result.cashWeight()).isEqualTo(0.0);
    }
}
