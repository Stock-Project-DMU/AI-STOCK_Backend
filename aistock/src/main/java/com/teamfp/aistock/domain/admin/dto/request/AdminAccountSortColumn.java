package com.teamfp.aistock.domain.admin.dto.request;

/**
 * 관리자 계좌 목록 열 제목 정렬(feat/admin-improvements) — 화면 표의 열과 1:1. sortColumn으로 보내면 정렬 선택칸
 * (sortBy)보다 우선하고, 방향은 direction(ASC/DESC)이다. USER는 계좌 주인 이름 순이다.
 */
public enum AdminAccountSortColumn {
    ACCOUNT_NUMBER,
    USER,
    BALANCE,
    OPENED_AT,
    STATUS
}
