package com.teamfp.aistock.domain.account.entity;

/**
 * 잔고 변동 원장(ADMIN_API_BACKEND_HANDOFF.md 4.3)의 거래 유형. 문서가 제시한 7종을 그대로 쓴다.
 */
public enum AccountTransactionType {
    INITIAL_GRANT,
    AUTO_CHARGE,
    ADMIN_CHARGE,
    ADMIN_DEDUCTION,
    ORDER_BUY,
    ORDER_SELL,
    ORDER_REFUND
}
