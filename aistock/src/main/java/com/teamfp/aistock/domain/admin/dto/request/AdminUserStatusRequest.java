package com.teamfp.aistock.domain.admin.dto.request;

import com.teamfp.aistock.domain.user.entity.UserStatus;

import jakarta.validation.constraints.NotNull;

/**
 * 관리자 — 사용자 활성상태 변경 요청 DTO. ACTIVE로 보내면 정지 해제, SUSPENDED로 보내면
 * 로그인 자체를 차단한다(CLAUDE.md 8번 — users.status는 관리자에 의한 로그인 차단 용도).
 */
public record AdminUserStatusRequest(

        @NotNull(message = "변경할 상태값은 필수입니다.")
        UserStatus status,
        @jakarta.validation.constraints.NotBlank
        @jakarta.validation.constraints.Size(max = 500)
        String reason,
        @jakarta.validation.constraints.Min(1)
        @jakarta.validation.constraints.Max(365)
        Integer durationDays
) {
}
