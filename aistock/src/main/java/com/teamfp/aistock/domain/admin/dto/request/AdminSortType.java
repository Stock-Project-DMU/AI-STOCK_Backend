package com.teamfp.aistock.domain.admin.dto.request;

/**
 * 관리자 목록 정렬(feat/admin-improvements) — 거래내역·충전 요청·충전·차감 이력 공용. 화면의 정렬 선택칸 값 그대로다.
 * LATEST(최신순, 기본) / OLDEST(오래된순) / AMOUNT_DESC(금액 큰순) / AMOUNT_ASC(금액 작은순).
 */
public enum AdminSortType {
    LATEST,
    OLDEST,
    AMOUNT_DESC,
    AMOUNT_ASC
}
