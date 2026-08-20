package com.teamfp.aistock.domain.ai.dto;

import java.util.List;

/**
 * simulations.scenario_data JSON 컬럼의 저장 형태를 그대로 미러링하는 Jackson 매핑 전용 타입.
 * 필드명(best/base/worst)이 곧 JSON 키이므로 임의로 바꾸지 않는다.
 */
public record ScenarioDataJson(
        List<ScenarioPointDto> best,
        List<ScenarioPointDto> base,
        List<ScenarioPointDto> worst
) {
}
