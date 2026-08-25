package com.teamfp.aistock.domain.ai.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// content 길이를 제한하지 않으면 ai_planning_messages.content(TEXT, 최대 약 6만자)를 넘길 수 있고,
// 무엇보다 Gemini 호출 토큰 비용이 사용자 입력 길이에 비례해 커진다 — 2000자는 채팅 메시지로
// 충분히 넉넉하면서 위 두 위험을 막을 수 있는 값이다.
public record AiChatRequest(
        @NotBlank @Size(max = 2000, message = "메시지는 2000자 이내로 입력해 주세요.") String content
) {
}
