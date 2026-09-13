package com.teamfp.aistock.domain.user.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 내 정보 수정 요청 DTO. loginId/비밀번호/투자성향은 이 API로 바꾸지 않는다(각각 별도 기능).
 */
public record UpdateUserRequest(

        @NotBlank(message = "이름은 필수입니다.")
        @Size(max = 50, message = "이름은 50자를 초과할 수 없습니다.")
        String name,

        // 부분 수정(email 생략)을 허용하면 null이 그대로 반영되어 기존 이메일이 지워지므로
        // name과 마찬가지로 필수값으로 받는다 — 이 API는 항상 name+email을 함께 교체한다.
        @NotBlank(message = "이메일은 필수입니다.")
        @Email(message = "올바른 이메일 형식이 아닙니다.")
        @Size(max = 100, message = "이메일은 100자를 초과할 수 없습니다.")
        String email,
        @jakarta.validation.constraints.Past java.time.LocalDate birthdate
) {
    public UpdateUserRequest(String name, String email) { this(name, email, null); }
}
