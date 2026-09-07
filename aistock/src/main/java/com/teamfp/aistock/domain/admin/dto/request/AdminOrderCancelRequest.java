package com.teamfp.aistock.domain.admin.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * 관리자 주문 강제취소 요청 DTO(ADMIN_API_BACKEND_HANDOFF.md 3.3).
 */
public record AdminOrderCancelRequest(
        @NotBlank(message = "취소 사유는 필수 입력 값입니다.") String reason
) {
}
