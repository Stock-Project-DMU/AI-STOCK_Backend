package com.teamfp.aistock.domain.admin.dto.request;

/**
 * 관리자 충전·차감 이력 열 제목 정렬(feat/admin-improvements) — 화면 표의 열과 1:1. sortColumn으로 보내면 정렬
 * 선택칸(sortBy)보다 우선하고, 방향은 direction(ASC/DESC)이다. TYPE은 유형 이름 순(거절 건은 REJECTED),
 * AMOUNT는 증감액의 절댓값(거절 건은 요청 금액) 순이다. 거절 건은 거래ID·거래 전후 잔액이 없어(NULL) 오름차순이면
 * 맨 앞, 내림차순이면 맨 뒤에 온다. CHARGE_REQUEST_ID는 관련 요청 번호, PROCESSED_BY는 처리 관리자 아이디 순이다.
 */
public enum AdminChargeHistorySortColumn {
    TRANSACTION_ID,
    ACCOUNT_NUMBER,
    USER,
    TYPE,
    AMOUNT,
    BALANCE_BEFORE,
    BALANCE_AFTER,
    CHARGE_REQUEST_ID,
    PROCESSED_BY,
    REASON,
    OCCURRED_AT
}
