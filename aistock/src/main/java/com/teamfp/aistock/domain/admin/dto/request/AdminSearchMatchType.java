package com.teamfp.aistock.domain.admin.dto.request;

/**
 * 관리자 목록 검색 방식(feat/admin-improvements). 엑셀 필터의 "포함"/"같음"처럼, 검색어가 들어간 값을 모두 찾을지
 * 검색어와 정확히 같은 값만 찾을지 고른다. 기본값은 CONTAINS다. 회원번호·주문번호처럼 숫자 ID 항목은 이 값과
 * 관계없이 항상 정확히 일치로 찾는다("12"로 120번이 나오지 않게).
 */
public enum AdminSearchMatchType {
    CONTAINS,
    EXACT
}
