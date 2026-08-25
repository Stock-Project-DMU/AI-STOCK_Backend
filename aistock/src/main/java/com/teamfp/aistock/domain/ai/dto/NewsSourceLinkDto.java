package com.teamfp.aistock.domain.ai.dto;

/**
 * news_briefings.source_links JSON 컬럼의 저장 형태를 그대로 미러링하는 Jackson 매핑 전용 타입
 * (ScenarioDataJson과 동일한 패턴). 브리핑 요약이 어떤 기사를 근거로 만들어졌는지 사용자에게
 * 밝히고, 원문 기사로 바로 이동할 수 있도록 링크까지 함께 저장한다(2026-08-24 사용자 요청).
 */
public record NewsSourceLinkDto(String title, String link, String outlet) {
}
