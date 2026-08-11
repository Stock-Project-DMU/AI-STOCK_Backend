package com.teamfp.aistock.domain.stock.dto;

/**
 * 전일 종가 대비 등락 방향. LsTickData에 별도 방향 필드가 없어 changeRate 부호로 판단한다
 * (changeRate > 0이면 UP, < 0이면 DOWN, 0이면 FLAT).
 */
public enum PriceDirection {
    UP,
    DOWN,
    FLAT;

    public static PriceDirection fromChangeRate(double changeRate) {
        if (changeRate > 0) {
            return UP;
        }
        if (changeRate < 0) {
            return DOWN;
        }
        return FLAT;
    }
}
