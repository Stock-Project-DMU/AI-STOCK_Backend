package com.teamfp.aistock.domain.admin.dto.request;

/**
 * 관리자 알림 관리(보낸 공지) 열 제목 정렬(feat/admin-improvements) — 화면 표의 열과 1:1. sortColumn으로 보내면 정렬
 * 선택칸(sortBy)보다 우선하고, 방향은 direction(ASC/DESC)이다. CREATED_BY는 보낸 관리자 아이디 순, POPUP_END_DATE는
 * 팝업 기한 순(일반 공지는 기한이 없어 오름차순이면 맨 앞)이다.
 */
public enum AdminNoticeSortColumn {
    NOTICE_ID,
    TITLE,
    TYPE,
    TARGET_TYPE,
    SENT_COUNT,
    POPUP_END_DATE,
    CREATED_BY,
    CREATED_AT
}
