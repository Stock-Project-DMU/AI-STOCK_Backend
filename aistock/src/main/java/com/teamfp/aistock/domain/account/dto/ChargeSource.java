package com.teamfp.aistock.domain.account.dto;

/**
 * 충전 이력(GET /api/accounts/{accountId}/charge-history) 항목의 충전 주체.
 * SELF = 사용자 직접 충전(3회 한도), ADMIN = 관리자 승인 충전 요청 또는 관리자 수동 지급.
 */
public enum ChargeSource {
    SELF,
    ADMIN
}
