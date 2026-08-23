package com.teamfp.aistock.domain.inquiry.dto.response;

import java.time.LocalDateTime;

import com.teamfp.aistock.domain.inquiry.entity.Inquiry;
import com.teamfp.aistock.domain.inquiry.entity.InquiryStatus;

/**
 * 문의 조회 응답 DTO. 미답변(PENDING) 상태면 answer/answeredAt은 null로 내려간다.
 */
public record InquiryResponse(
        Long inquiryId,
        String title,
        String content,
        InquiryStatus status,
        String answer,
        LocalDateTime answeredAt,
        LocalDateTime createdAt
) {

    public static InquiryResponse from(Inquiry inquiry) {
        return new InquiryResponse(
                inquiry.getInquiryId(),
                inquiry.getTitle(),
                inquiry.getContent(),
                inquiry.getStatus(),
                inquiry.getAnswer(),
                inquiry.getAnsweredAt(),
                inquiry.getCreatedAt()
        );
    }
}
