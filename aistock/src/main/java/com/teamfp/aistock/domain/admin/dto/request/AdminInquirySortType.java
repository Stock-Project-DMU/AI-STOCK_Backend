package com.teamfp.aistock.domain.admin.dto.request;

/**
 * 관리자 문의 목록 정렬 선택칸(feat/admin-improvements) — PENDING_FIRST(답변 대기 먼저, 그 안에서 최신순 — 기본) /
 * LATEST(최신순) / OLDEST(오래된순).
 */
public enum AdminInquirySortType {
    PENDING_FIRST,
    LATEST,
    OLDEST
}
