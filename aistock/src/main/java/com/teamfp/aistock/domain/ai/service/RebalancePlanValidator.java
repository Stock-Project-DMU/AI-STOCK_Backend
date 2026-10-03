package com.teamfp.aistock.domain.ai.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.teamfp.aistock.domain.ai.dto.PortfolioAllocationDto;

/**
 * Gemini가 추천한 리밸런싱 구성을 서버 규칙으로 검증·정규화한다(feature/goal-simulation-v2,
 * 2026-10-01). Gemini는 후보 목록 안에서 종목과 비중을 고르고, 이상한 결과(목록 밖 종목, 비중
 * 범위 위반 등)는 여기서 걸러낸다.
 *
 * 규칙:
 * ① 후보 목록(allowedStockCodes) 밖 종목코드는 버린다. 같은 종목이 여러 번 오면 비중을 합친다.
 *    비중이 0 이하인 항목도 버린다.
 * ② 주식 종목은 1개 이상, 최대 {@value #MAX_STOCK_COUNT}개.
 * ③ 예수금 비중은 0~100%로 자른 뒤, 예수금 + 종목 비중 합이 100%가 되도록 전체를 비례 정규화한다.
 * ④ 정규화 후 종목 비중은 각각 {@value #MIN_STOCK_WEIGHT}~{@value #MAX_STOCK_WEIGHT}%여야 한다.
 * 위반하면 valid=false와 위반 사유를 돌려주고, 호출 측(SimulationService)이 그 사유를 붙여 Gemini에
 * 1회 다시 요청한다.
 *
 * 상태가 없는 순수 함수라 Spring 빈으로 두지 않는다(ScenarioCalculator와 동일).
 */
public final class RebalancePlanValidator {

    public static final int MAX_STOCK_COUNT = 10;
    public static final double MIN_STOCK_WEIGHT = 5.0;
    public static final double MAX_STOCK_WEIGHT = 40.0;

    // 소수 둘째 자리 반올림 오차 허용치
    private static final double WEIGHT_TOLERANCE = 0.01;

    private RebalancePlanValidator() {
    }

    /**
     * @param valid       규칙을 모두 만족하면 true
     * @param violation   위반 사유(Gemini 재요청 프롬프트에 그대로 붙인다). valid면 null
     * @param cashWeight  정규화된 예수금 비중(%)
     * @param allocations 정규화된 종목별 비중(stockName/monthlyGrowthRate는 아직 비어 있음)
     */
    public record Result(boolean valid, String violation, double cashWeight, List<PortfolioAllocationDto> allocations) {

        static Result invalid(String violation) {
            return new Result(false, violation, 0, List.of());
        }
    }

    public static Result validate(double suggestedCashWeight, List<PortfolioAllocationDto> suggestedAllocations,
                                  Set<String> allowedStockCodes) {
        Map<String, Double> weightByCode = new LinkedHashMap<>();
        for (PortfolioAllocationDto allocation : suggestedAllocations) {
            if (allocation.stockCode() == null || !allowedStockCodes.contains(allocation.stockCode())
                    || allocation.weight() <= 0) {
                continue;
            }
            weightByCode.merge(allocation.stockCode(), allocation.weight(), Double::sum);
        }

        if (weightByCode.isEmpty()) {
            return Result.invalid("후보 목록 안의 주식 종목을 1개 이상 포함해야 합니다.");
        }
        if (weightByCode.size() > MAX_STOCK_COUNT) {
            return Result.invalid("주식 종목은 최대 %d개까지만 담을 수 있습니다(현재 %d개)."
                    .formatted(MAX_STOCK_COUNT, weightByCode.size()));
        }

        double cashWeight = Math.max(0, Math.min(100, suggestedCashWeight));
        double total = cashWeight + weightByCode.values().stream().mapToDouble(Double::doubleValue).sum();
        double scale = 100.0 / total;

        List<PortfolioAllocationDto> normalized = new ArrayList<>();
        List<String> outOfRange = new ArrayList<>();
        for (Map.Entry<String, Double> entry : weightByCode.entrySet()) {
            double weight = round2(entry.getValue() * scale);
            if (weight < MIN_STOCK_WEIGHT - WEIGHT_TOLERANCE || weight > MAX_STOCK_WEIGHT + WEIGHT_TOLERANCE) {
                outOfRange.add("%s(%.2f%%)".formatted(entry.getKey(), weight));
            }
            normalized.add(new PortfolioAllocationDto(entry.getKey(), null, weight, 0));
        }
        if (!outOfRange.isEmpty()) {
            return Result.invalid("종목당 비중은 %.0f~%.0f%%여야 합니다. 범위를 벗어난 종목: %s"
                    .formatted(MIN_STOCK_WEIGHT, MAX_STOCK_WEIGHT, String.join(", ", outOfRange)));
        }

        double normalizedStockSum = normalized.stream().mapToDouble(PortfolioAllocationDto::weight).sum();
        return new Result(true, null, round2(Math.max(0, 100 - normalizedStockSum)), normalized);
    }

    private static double round2(double value) {
        return Math.round(value * 100) / 100.0;
    }
}
