package com.teamfp.aistock.domain.admin.dto.response;

/**
 * 관리자 "전체 활동 기록"의 탭(feat/admin-improvements) — 회원이력 / 거래이력 / 충전차감이력 / 문의이력.
 * "전체" 탭은 category 파라미터를 ALL로 보낸다.
 */
public enum AdminActivityCategory {
    MEMBER,
    TRADE,
    CHARGE,
    INQUIRY
}
