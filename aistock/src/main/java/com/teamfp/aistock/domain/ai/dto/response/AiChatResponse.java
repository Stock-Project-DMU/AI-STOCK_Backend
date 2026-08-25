package com.teamfp.aistock.domain.ai.dto.response;

import java.time.LocalDateTime;

import com.teamfp.aistock.domain.ai.entity.AiPlanningMessage;
import com.teamfp.aistock.domain.ai.entity.MessageRole;

public record AiChatResponse(
        Long messageId,
        MessageRole role,
        String content,
        LocalDateTime createdAt
) {

    public static AiChatResponse from(AiPlanningMessage message) {
        return new AiChatResponse(
                message.getMessageId(),
                message.getRole(),
                message.getContent(),
                message.getCreatedAt()
        );
    }
}
