package com.teamfp.aistock.domain.ai.dto.response;

import java.time.LocalDateTime;
import java.time.LocalTime;

import com.teamfp.aistock.domain.ai.entity.NewsChatSession;
import com.teamfp.aistock.global.util.NewsRelevanceMatcher;

public record NewsChatSessionResponse(
        Long sessionId, String outletDomain, String outletName, LocalTime deliveryTime,
        LocalDateTime createdAt, LocalDateTime updatedAt) {
    public static NewsChatSessionResponse from(NewsChatSession session) {
        String domain = session.getOutletDomain();
        String name = domain == null ? "전체 뉴스" : NewsRelevanceMatcher.OUTLET_NAMES.getOrDefault(domain, "선택한 언론사");
        return new NewsChatSessionResponse(session.getSessionId(), domain, name,
                session.getDeliveryTime(), session.getCreatedAt(), session.getUpdatedAt());
    }
}
