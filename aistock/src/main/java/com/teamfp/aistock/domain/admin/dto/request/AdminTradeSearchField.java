package com.teamfp.aistock.domain.admin.dto.request;

/**
 * 관리자 거래 목록 검색 항목(feat/admin-improvements). ALL은 아래 항목 전체에서 찾는다. 이전에는 회원 이름으로
 * 검색할 수 없었는데(화면 안내 문구는 "아이디 또는 이름") NAME을 추가했다. ORDER_ID(주문번호)는 숫자 검색어일
 * 때만, 항상 정확히 일치로 찾는다.
 */
public enum AdminTradeSearchField {
    ALL,
    ORDER_ID,
    LOGIN_ID,
    NAME,
    ACCOUNT_NUMBER,
    STOCK_CODE,
    STOCK_NAME
}
