package com.teamfp.aistock.domain.ai.dto.response;

import com.teamfp.aistock.domain.ai.entity.NewsBriefingSetting;
import com.teamfp.aistock.global.util.NewsRelevanceMatcher;

/**
 * 사용자가 현재 설정해둔 언론사 조회 응답 — GET /api/ai/news/settings.
 */
public record NewsBriefingSettingResponse(String outletDomain, String outletName) {

    public static NewsBriefingSettingResponse from(NewsBriefingSetting setting) {
        String outletName = NewsRelevanceMatcher.OUTLET_NAMES.getOrDefault(setting.getOutletDomain(), "확인된 매체");
        return new NewsBriefingSettingResponse(setting.getOutletDomain(), outletName);
    }
}
