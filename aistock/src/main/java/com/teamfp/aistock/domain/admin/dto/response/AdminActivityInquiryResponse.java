package com.teamfp.aistock.domain.admin.dto.response;

import java.time.LocalDateTime;

import com.teamfp.aistock.domain.inquiry.entity.InquiryStatus;

/**
 * 전체 활동 기록 — 문의 한 줄의 상세(feat/admin-improvements). 문의 등록과 답변이 한 줄에 담긴다 — answer(답변 내용)·
 * answeredAt·answeredByName은 답변된 문의일 때만 채워진다.
 */
public record AdminActivityInquiryResponse(
        Long inquiryId,
        String title,
        InquiryStatus status,
        String answer,
        LocalDateTime answeredAt,
        String answeredByName
) {
}
