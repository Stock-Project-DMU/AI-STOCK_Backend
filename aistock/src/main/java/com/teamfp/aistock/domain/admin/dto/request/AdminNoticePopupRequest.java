package com.teamfp.aistock.domain.admin.dto.request;

import java.time.LocalDate;

import jakarta.validation.constraints.NotNull;

/**
 * 공지 팝업 기한 변경 요청(feat/admin-improvements, PATCH /api/admin/notices/{noticeId}/popup). 오늘 이후(포함) 날짜만
 * 받는다 — 일반 공지에 보내면 이때부터 팝업이 된다. 바로 내리려면 PATCH .../popup-end를 쓴다.
 */
public record AdminNoticePopupRequest(
        @NotNull(message = "팝업 게시 종료일(popupEndDate)은 필수입니다.")
        LocalDate popupEndDate
) {
}
