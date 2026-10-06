package com.teamfp.aistock.domain.admin.dto.response;

/**
 * 충전·차감 이력 한 줄의 종류(feat/admin-improvements).
 * - TRANSACTION: 실제로 잔고가 바뀐 기록(셀프 충전·차감, 요청 승인 충전, 관리자 지급·차감)
 * - REJECTED_REQUEST: 거절된 충전 요청 — 잔고는 그대로지만 "처리 끝난 요청"이라 이력에 함께 보여준다
 */
public enum AdminChargeHistoryEntryType {
    TRANSACTION,
    REJECTED_REQUEST
}
