package com.teamfp.aistock.domain.admin.dto.request;

/**
 * 관리자 충전·차감 이력 검색 항목(feat/admin-improvements). ALL은 아래 항목 전체에서 찾는다.
 * TRANSACTION_ID(내역 번호)는 숫자 검색어일 때만, 항상 정확히 일치로 찾는다.
 */
public enum AdminAccountTransactionSearchField {
    ALL,
    TRANSACTION_ID,
    LOGIN_ID,
    NAME,
    ACCOUNT_NUMBER
}
