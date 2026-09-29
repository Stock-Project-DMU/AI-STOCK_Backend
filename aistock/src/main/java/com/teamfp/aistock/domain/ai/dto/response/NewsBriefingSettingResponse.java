package com.teamfp.aistock.domain.ai.dto.response;

import java.time.LocalDateTime;
import java.time.LocalTime;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.teamfp.aistock.domain.ai.entity.NewsBriefingSetting;
import com.teamfp.aistock.global.util.NewsRelevanceMatcher;

/**
 * 사용자가 현재 설정해둔 언론사·브리핑 시각 조회 응답 — GET /api/ai/news/settings.
 * 프론트엔드에는 deliveryTime을 제공하고 기존 응답 이름인 briefingTime도 유지한다.
 */
public record NewsBriefingSettingResponse(String outletDomain, String outletName, LocalTime deliveryTime, LocalDateTime lastAttemptAt) {

    public static NewsBriefingSettingResponse from(NewsBriefingSetting setting) {
        String outletName = NewsRelevanceMatcher.OUTLET_NAMES.getOrDefault(setting.getOutletDomain(), "확인된 매체");
        return new NewsBriefingSettingResponse(setting.getOutletDomain(), outletName, setting.getBriefingTime(), setting.getLastAttemptAt());
    }

    @JsonProperty("briefingTime")
    public LocalTime briefingTime() {
        return deliveryTime;
    }
}
