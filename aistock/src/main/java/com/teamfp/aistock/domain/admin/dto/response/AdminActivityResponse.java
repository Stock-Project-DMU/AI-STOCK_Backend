package com.teamfp.aistock.domain.admin.dto.response;

import java.time.LocalDateTime;

/**
 * 관리자 "전체 활동 기록" 한 줄(feat/admin-improvements). 공통 필드(종류·탭·시각·회원) 아래에 종류에 맞는 상세 하나만
 * 채워지고 나머지는 null이다.
 *
 * - id: 종류별 원본 ID — SIGNUP·WITHDRAWAL=userId, USER_STATUS·ACCOUNT_STATUS·ADMIN_CREATE=auditLogId, TRADE=orderId,
 *   CHARGE_REQUEST=requestId, SELF_BALANCE·ADMIN_BALANCE=transactionId, INQUIRY=inquiryId
 * - occurredAt: 그 일이 시작된 시각(가입·탈퇴 시각, 처리 시각, 주문 시각, 충전 요청 시각, 문의 등록 시각) — 나중에
 *   승인·체결·답변이 돼도 순서는 바뀌지 않는다
 * - userId/userName/loginId: 그 일의 대상 회원(관리자 계정 생성이면 생성된 관리자)
 * - member/trade/charge/balance/inquiry: 종류별 상세(해당하는 것 하나만)
 */
public record AdminActivityResponse(
        AdminActivityType activityType,
        AdminActivityCategory category,
        Long id,
        LocalDateTime occurredAt,
        Long userId,
        String userName,
        String loginId,
        AdminActivityMemberResponse member,
        AdminActivityTradeResponse trade,
        AdminActivityChargeResponse charge,
        AdminActivityBalanceResponse balance,
        AdminActivityInquiryResponse inquiry
) {
}
