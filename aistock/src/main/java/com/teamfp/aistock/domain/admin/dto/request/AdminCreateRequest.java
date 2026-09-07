package com.teamfp.aistock.domain.admin.dto.request;

import com.teamfp.aistock.global.util.MaxByteSize;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 관리자 신규 생성 요청 DTO(ADMIN_API_BACKEND_HANDOFF.md 5.1). "구현 전 결정이 필요한 정책" 6번
 * (기존 회원 승격 방식 vs 별도 생성 방식)이 아직 팀 확정 전이라, 우선 "별도 생성 방식"(문서가
 * 제시한 요청 예시 그대로)으로 구현해뒀다 — 승격 방식으로 정책이 정해지면 이 API 자체를
 * 제거하고 기존 `PATCH /api/admin/users/{userId}/status`류로 역할만 바꾸는 방향으로 대체해야 한다.
 * password 정책은 `SignupRequest.password`와 동일하다.
 */
public record AdminCreateRequest(
        @NotBlank(message = "아이디는 필수 입력 값입니다.") String loginId,

        @NotBlank(message = "비밀번호는 필수 입력 값입니다.")
        @Pattern(
                regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,}$",
                message = "비밀번호는 8자 이상, 영문과 숫자를 포함해야 합니다."
        )
        @MaxByteSize(max = 72, message = "비밀번호는 72바이트를 초과할 수 없습니다.")
        String password,

        @NotBlank(message = "이름은 필수 입력 값입니다.") String name,

        @NotBlank(message = "이메일은 필수 입력 값입니다.")
        @Email(message = "올바른 이메일 형식이 아닙니다.")
        String email
) {
}
