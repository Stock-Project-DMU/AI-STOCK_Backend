package com.teamfp.aistock.domain.user.dto.request;

import com.teamfp.aistock.domain.user.entity.InvestmentLevel;
import jakarta.validation.constraints.*;

public record InvestmentProfileUpdateRequest(@Min(1) @Max(5) int investmentTendency,
        @Min(1) @Max(4) int fundTendency, @NotNull InvestmentLevel investmentLevel) {}
