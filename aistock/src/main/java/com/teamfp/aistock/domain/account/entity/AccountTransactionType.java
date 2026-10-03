package com.teamfp.aistock.domain.account.entity;

/**
 * 잔고 변동 원장(ADMIN_API_BACKEND_HANDOFF.md 4.3)의 거래 유형. 문서가 제시한 7종에
 * 예치금 이자(INTEREST)와 매도 거래 수수료(TRADE_FEE)를 더했다(feature/mypage-improvement).
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
    TRADE_FEE
}
