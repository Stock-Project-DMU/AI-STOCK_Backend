package com.teamfp.aistock.domain.admin.dto.request;

/**
 * 관리자 계좌 목록 정렬(feat/admin-improvements) — LATEST(최신 개설순, 기본) / BALANCE_DESC(잔고 많은순) /
 * BALANCE_ASC(잔고 적은순).
 */
public enum AdminAccountSortType {
    LATEST,
    BALANCE_DESC,
    BALANCE_ASC
}
