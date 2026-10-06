package com.teamfp.aistock.domain.account.entity;

/**
 * 잔고 변동 원장(ADMIN_API_BACKEND_HANDOFF.md 4.3)의 거래 유형. 문서가 제시한 7종에
 * 예치금 이자(INTEREST)와 매도 거래 수수료(TRADE_FEE)를 더했다(feature/mypage-improvement).
 * AUTO_DEDUCTION은 관리자 계정 본인 계좌 직접 차감이다(feat/admin-improvements) — 관리자가 사용자 계좌에서
 * 빼는 ADMIN_DEDUCTION과 계좌 내역에서 구분하려고 AUTO_CHARGE/ADMIN_CHARGE처럼 나눴다.
 * 보유 종목 현금배당 입금(DIVIDEND)을 더했다(feature/dividend).
 */
public enum AccountTransactionType {
    INITIAL_GRANT,
    AUTO_CHARGE,
    ADMIN_CHARGE,
    ADMIN_DEDUCTION,
    ORDER_BUY,
    ORDER_SELL,
    ORDER_REFUND,
    INTEREST,
    TRADE_FEE,
    AUTO_DEDUCTION,
    DIVIDEND
}
