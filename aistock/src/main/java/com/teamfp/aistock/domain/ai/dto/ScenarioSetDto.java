package com.teamfp.aistock.domain.ai.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * ScenarioCalculator.calculate()의 반환 타입. best/base/worst 각각의 곡선과
 * 목표 금액 도달일을 함께 담는다. reachDate는 targetMonths 안에 도달하지
 * 못하면 null이다.
 */
public record ScenarioSetDto(
        List<ScenarioPointDto> bestPoints,
        List<ScenarioPointDto> basePoints,
        List<ScenarioPointDto> worstPoints,
        LocalDate bestReachDate,
        LocalDate baseReachDate,
        LocalDate worstReachDate
) {
}
