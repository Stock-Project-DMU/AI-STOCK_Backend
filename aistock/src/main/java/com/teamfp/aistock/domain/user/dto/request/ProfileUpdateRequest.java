package com.teamfp.aistock.domain.user.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ProfileUpdateRequest(
        @NotBlank String currentPassword,
        @NotNull @Valid UpdateUserRequest user,
        @Valid InvestmentProfileUpdateRequest investment,
        @Valid PasswordChangeRequest passwordChange
) {}
