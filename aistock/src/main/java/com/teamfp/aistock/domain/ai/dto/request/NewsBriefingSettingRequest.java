package com.teamfp.aistock.domain.ai.dto.request;

import java.time.LocalTime;

import jakarta.validation.constraints.NotBlank;

/**
 * 사용자가 고른 언론사 도메인(예: "hankyung.com")과 브리핑 생성 희망 시각(시:분:초, KST).
 * outletDomain은 GET /api/ai/news/outlets가 내려주는 목록 중 하나여야 하며,
 * AiNewsService가 저장 전 NewsRelevanceMatcher.OUTLET_NAMES에 등록된 값인지 검증한다.
 * briefingTime은 "HH:mm:ss"(예: "22:15:30") 문자열로 주고받는다. 아직 이 값을 보내지
 * 않는 구버전 프론트와의 호환을 위해 선택 항목으로 두며, 생략 시 AiNewsService가
 * NewsBriefingSetting.DEFAULT_BRIEFING_TIME(07:00:00)을 적용한다.
 */
public record NewsBriefingSettingRequest(
        @NotBlank String outletDomain,
        LocalTime briefingTime
) {
}
