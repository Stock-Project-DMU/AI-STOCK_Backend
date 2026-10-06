package com.teamfp.aistock.domain.admin.dto.request;

/**
 * 관리자 충전 요청 목록 열 제목 정렬(feat/admin-improvements) — 화면 표의 열과 1:1. sortColumn으로 보내면 정렬
 * 선택칸(sortBy)보다 우선하고, 방향은 direction(ASC/DESC)이다.
 */
public enum AdminChargeRequestSortColumn {
    REQUEST_ID,
    USER,
    ACCOUNT_NUMBER,
    AMOUNT,
    REQUESTED_AT,
    STATUS
}
