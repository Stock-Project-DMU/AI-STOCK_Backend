package com.teamfp.aistock.domain.ai.dto.request;

import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public record NewsChatRequest(
        @NotBlank @Size(max = 2000) String content,
        @NotNull @Size(max = 8) List<@Valid Turn> history) {
    public record Turn(@NotNull @Pattern(regexp = "USER|ASSISTANT") String role,
                       @NotBlank @Size(max = 6000) String content) {}
}
