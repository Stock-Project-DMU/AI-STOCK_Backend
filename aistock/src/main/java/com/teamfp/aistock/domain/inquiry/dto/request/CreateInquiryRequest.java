package com.teamfp.aistock.domain.inquiry.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 문의 작성 요청 DTO. 길이 제한은 프론트 입력 제한(제목 100자·내용 500자)과 맞춘다 —
 * title은 inquiries.title(VARCHAR(200)), content는 TEXT라 DB 한도 안이다(NAMING.md 8-34).
 */
public record CreateInquiryRequest(

        @NotBlank(message = "제목은 필수입니다.")
        @Size(max = CreateInquiryRequest.TITLE_MAX_LENGTH, message = "제목은 100자를 초과할 수 없습니다.")
        String title,

        @NotBlank(message = "내용은 필수입니다.")
        @Size(max = CreateInquiryRequest.CONTENT_MAX_LENGTH, message = "내용은 500자를 초과할 수 없습니다.")
        String content
) {

    public static final int TITLE_MAX_LENGTH = 100;
    public static final int CONTENT_MAX_LENGTH = 500;
}
