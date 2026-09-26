package com.teamfp.aistock.domain.ai.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AiPlanningSessionTitleRequest(
        @NotBlank(message = "채팅명을 입력해 주세요.")
        @Size(max = 100, message = "채팅명은 100자 이내로 입력해 주세요.")
        String title
) {
}
