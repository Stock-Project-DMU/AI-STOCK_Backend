package com.teamfp.aistock.domain.ai.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * 사용자가 고른 언론사 도메인(예: "hankyung.com") — GET /api/ai/news/outlets가 내려주는
 * 목록 중 하나여야 한다. AiNewsService가 저장 전 NewsRelevanceMatcher.OUTLET_NAMES에
 * 등록된 값인지 검증한다.
 */
public record NewsBriefingSettingRequest(@NotBlank String outletDomain) {
}
