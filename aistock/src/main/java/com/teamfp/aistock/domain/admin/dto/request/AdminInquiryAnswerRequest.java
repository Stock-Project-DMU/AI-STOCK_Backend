package com.teamfp.aistock.domain.admin.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 관리자 — 문의 답변 등록/수정 요청 DTO. 이미 ANSWERED인 문의에 다시 보내면
 * 기존 답변을 덮어쓴다(오타 정정 등 재답변 필요 상황을 막지 않기 위한 정책 —
 * NAMING.md 8-18 참고). 길이는 관리자 화면 입력 제한(500자)과 맞춘다(NAMING.md 8-34).
 */
public record AdminInquiryAnswerRequest(

        @NotBlank(message = "답변 내용은 필수입니다.")
        @Size(max = AdminInquiryAnswerRequest.ANSWER_MAX_LENGTH, message = "답변은 500자를 초과할 수 없습니다.")
        String answer
) {

    public static final int ANSWER_MAX_LENGTH = 500;
}
