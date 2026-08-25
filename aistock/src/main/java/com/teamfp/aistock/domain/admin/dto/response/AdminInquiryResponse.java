package com.teamfp.aistock.domain.admin.dto.response;

import java.time.LocalDateTime;

import com.teamfp.aistock.domain.inquiry.entity.Inquiry;
import com.teamfp.aistock.domain.inquiry.entity.InquiryStatus;

/**
 * 관리자 — 문의 목록·상세 조회 응답 DTO. 사용자용 InquiryResponse와 달리 어느
 * 사용자가 보낸 문의인지 식별할 수 있도록 userName/loginId를 함께 담는다.
 */
public record AdminInquiryResponse(
        Long inquiryId,
        String userName,
        String loginId,
        String title,
        String content,
        InquiryStatus status,
        String answer,
        LocalDateTime answeredAt,
        LocalDateTime createdAt
) {

    public static AdminInquiryResponse from(Inquiry inquiry) {
        return new AdminInquiryResponse(
                inquiry.getInquiryId(),
                inquiry.getUser().getName(),
                inquiry.getUser().getLoginId(),
                inquiry.getTitle(),
                inquiry.getContent(),
                inquiry.getStatus(),
                inquiry.getAnswer(),
                inquiry.getAnsweredAt(),
                inquiry.getCreatedAt()
        );
    }
}
