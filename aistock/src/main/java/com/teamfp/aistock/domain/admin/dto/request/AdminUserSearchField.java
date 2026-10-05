package com.teamfp.aistock.domain.admin.dto.request;

/**
 * 관리자 회원·관리자 목록 검색 항목(feat/admin-improvements). ALL은 아래 항목 전체에서 찾는다.
 * USER_ID(회원번호)는 숫자 검색어일 때만, 항상 정확히 일치로 찾는다.
 */
public enum AdminUserSearchField {
    ALL,
    USER_ID,
    LOGIN_ID,
    NAME,
    EMAIL
}
