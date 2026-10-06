package com.teamfp.aistock.domain.stock.entity;

/**
 * 배당 권리 상태(feature/dividend). 배당락일에 PENDING으로 생성되고, 지급일에 예수금이 입금되면
 * PAID가 된다. 지급 시점에 계좌가 없어진(탈퇴) 권리는 SKIPPED로 닫는다.
 */
public enum DividendEntitlementStatus {
    PENDING,
    PAID,
    SKIPPED
}
