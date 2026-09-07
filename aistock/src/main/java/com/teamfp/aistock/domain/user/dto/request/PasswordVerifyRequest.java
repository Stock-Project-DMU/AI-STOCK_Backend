package com.teamfp.aistock.domain.user.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * 비밀번호 확인 요청 DTO(ADMIN_API_BACKEND_HANDOFF.md 5.3). 민감한 작업(관리자 생성/상태 변경
 * 등) 전 재인증 용도로 쓸 수 있게 마련해둔 API — 실제 재인증 강제 여부는 handoff 문서가
 * "별도 결정 필요"로 남겨둔 항목이라, 이번 범위에서는 확인 API 자체만 제공한다.
 */
public record PasswordVerifyRequest(
        @NotBlank(message = "비밀번호는 필수 입력 값입니다.") String password
) {
}
