package com.teamfp.aistock.domain.admin.dto.response;

import java.time.LocalDateTime;

import com.teamfp.aistock.domain.inquiry.entity.Inquiry;
import com.teamfp.aistock.domain.inquiry.entity.InquiryStatus;

/**
 * 관리자 — 문의 목록·상세 조회 응답 DTO. 사용자용 InquiryResponse와 달리 어느
 * 사용자가 보낸 문의인지 식별할 수 있도록 userId/userName/loginId를 함께 담는다.
 * answeredByName은 답변한 관리자 이름(feat/admin-improvements, 답변 전·관리자 탈퇴 시 null).
 */
public record AdminInquiryResponse(
        Long inquiryId,
        Long userId,
        String userName,
        String loginId,
        String title,
        String content,
        InquiryStatus status,
        String answer,
        String answeredByName,
        LocalDateTime answeredAt,
        LocalDateTime createdAt
) {

    public static AdminInquiryResponse from(Inquiry inquiry) {
        return new AdminInquiryResponse(
                inquiry.getInquiryId(),
                inquiry.getUser().getUserId(),
                inquiry.getUser().getName(),
                inquiry.getUser().getLoginId(),
                inquiry.getTitle(),
                inquiry.getContent(),
                inquiry.getStatus(),
                inquiry.getAnswer(),
                inquiry.getAnsweredBy() == null ? null : inquiry.getAnsweredBy().getName(),
                inquiry.getAnsweredAt(),
                inquiry.getCreatedAt()
        );
    }
}
