package com.teamfp.aistock.domain.admin.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * 관리자 — 문의 답변 등록/수정 요청 DTO. 이미 ANSWERED인 문의에 다시 보내면
 * 기존 답변을 덮어쓴다(오타 정정 등 재답변 필요 상황을 막지 않기 위한 정책 —
 * NAMING.md 8-18 참고).
 */
public record AdminInquiryAnswerRequest(

        @NotBlank(message = "답변 내용은 필수입니다.")
        String answer
) {
}
