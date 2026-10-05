package com.teamfp.aistock.domain.admin.dto.request;

/**
 * 관리자 회원·관리자 목록 정렬(feat/admin-improvements) — LATEST(최신 가입순, 기본) / OLDEST(오래된 가입순) /
 * NAME(이름 가나다순).
 */
public enum AdminUserSortType {
    LATEST,
    OLDEST,
    NAME
}
