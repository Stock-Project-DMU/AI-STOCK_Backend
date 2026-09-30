package com.teamfp.aistock.domain.ai.dto.request;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record NewsChatMessageRequest(
        @NotBlank @Size(max = 2000) String content,
        LocalDate briefingDate) {}
