package com.teamfp.aistock.domain.auth.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record RecoveryEmailCodeRequest(
        @NotNull RecoveryPurpose purpose,
        @Size(max = 50) String loginId,
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Email String email,
        LocalDate birthdate
) {
    public enum RecoveryPurpose {
        FIND_ID,
        RESET_PASSWORD
    }
}
