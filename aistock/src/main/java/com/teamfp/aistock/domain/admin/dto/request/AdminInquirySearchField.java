package com.teamfp.aistock.domain.admin.dto.request;

/**
 * 관리자 문의 목록 검색 항목(feat/admin-improvements) — ALL(전체, 기본) / INQUIRY_ID(문의번호, 정확히 일치) /
 * LOGIN_ID(작성자 아이디) / NAME(작성자 이름) / TITLE(문의 제목).
 */
public enum AdminInquirySearchField {
    ALL,
    INQUIRY_ID,
    LOGIN_ID,
    NAME,
    TITLE
}
