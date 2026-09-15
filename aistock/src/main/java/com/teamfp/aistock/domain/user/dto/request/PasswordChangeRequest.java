package com.teamfp.aistock.domain.user.dto.request;

import com.teamfp.aistock.global.util.MaxByteSize;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 비밀번호 변경 요청 DTO(ADMIN_API_BACKEND_HANDOFF.md 5.3). newPassword는 회원가입
 * (SignupRequest)과 동일한 정책(8자 이상, 영문+숫자 포함, 72바이트 이하)을 그대로 적용한다.
 */
public record PasswordChangeRequest(
        @NotBlank(message = "현재 비밀번호는 필수 입력 값입니다.") String currentPassword,

        @NotBlank(message = "새 비밀번호는 필수 입력 값입니다.")
        @Pattern(
                regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,}$",
                message = "비밀번호는 8자 이상, 영문과 숫자를 포함해야 합니다."
        )
        @MaxByteSize(max = 72, message = "비밀번호는 72바이트를 초과할 수 없습니다.")
        String newPassword
) {
}
