package com.teamfp.aistock.domain.auth.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import com.teamfp.aistock.global.util.MaxByteSize;
import java.time.LocalDate;

public record AccountRecoveryRequest(
        @Size(max = 50) String loginId,
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Email String email,
        LocalDate birthdate,
        @NotBlank @Pattern(regexp = "[0-9]{6}") String code,
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,}$") @MaxByteSize(max = 72) String newPassword
) {}
