package com.teamfp.aistock.domain.admin.dto.request;

/**
 * 관리자 알림 관리(보낸 공지) 검색 항목(feat/admin-improvements) — ALL(전체, 기본) / NOTICE_ID(공지 번호, 정확히 일치) /
 * TITLE(제목) / ADMIN_LOGIN_ID(보낸 관리자 아이디).
 */
public enum AdminNoticeSearchField {
    ALL,
    NOTICE_ID,
    TITLE,
    ADMIN_LOGIN_ID
}
