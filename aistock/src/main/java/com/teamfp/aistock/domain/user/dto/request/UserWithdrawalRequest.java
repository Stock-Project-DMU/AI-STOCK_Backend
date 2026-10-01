package com.teamfp.aistock.domain.user.dto.request;

import jakarta.validation.constraints.Email;

/** Regular accounts confirm their password; social accounts confirm their registered email. */
public record UserWithdrawalRequest(
        String password,
        @Email(message = "올바른 이메일을 입력해 주세요.") String email
) {}
