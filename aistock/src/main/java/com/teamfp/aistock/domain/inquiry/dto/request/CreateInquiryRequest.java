package com.teamfp.aistock.domain.inquiry.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 문의 작성 요청 DTO. title은 inquiries.title(VARCHAR(200))에 맞춰 길이를 제한한다.
 */
public record CreateInquiryRequest(

        @NotBlank(message = "제목은 필수입니다.")
        @Size(max = 200, message = "제목은 200자를 초과할 수 없습니다.")
        String title,

        @NotBlank(message = "내용은 필수입니다.")
        String content
) {
}
