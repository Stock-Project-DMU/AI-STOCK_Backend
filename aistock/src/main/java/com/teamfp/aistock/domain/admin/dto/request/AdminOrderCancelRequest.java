package com.teamfp.aistock.domain.admin.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 관리자 주문 강제취소 요청 DTO(관리자 API — 전체 거래 관리).
 */
public record AdminOrderCancelRequest(
        @NotBlank(message = "취소 사유는 필수 입력 값입니다.")
        @Size(max = 500, message = "취소 사유는 500자를 넘을 수 없습니다.")
        String reason
) {
}
