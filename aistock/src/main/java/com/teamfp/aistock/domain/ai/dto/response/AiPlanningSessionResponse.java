package com.teamfp.aistock.domain.ai.dto.response;

import java.time.LocalDateTime;

import com.teamfp.aistock.domain.ai.entity.AiPlanningSession;
import com.teamfp.aistock.domain.ai.entity.SessionStatus;

public record AiPlanningSessionResponse(
        Long sessionId,
        String title,
        SessionStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public static AiPlanningSessionResponse from(AiPlanningSession session) {
        return new AiPlanningSessionResponse(
                session.getSessionId(),
                session.getTitle(),
                session.getStatus(),
                session.getCreatedAt(),
                session.getUpdatedAt()
        );
    }
}
