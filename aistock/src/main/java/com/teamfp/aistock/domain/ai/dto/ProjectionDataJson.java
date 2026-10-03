package com.teamfp.aistock.domain.ai.dto;

/**
 * simulations.projection_data JSON 컬럼의 저장 형태를 그대로 미러링하는 Jackson 매핑 전용 타입.
 * 필드명이 곧 JSON 키이므로 임의로 바꾸지 않는다.
 *
 * @param holdingsAmount 시작 금액 중 보유종목 평가금액
 * @param cashAmount     시작 금액 중 예수금(주문 대기로 묶인 금액 포함)
 * @param current        현재 보유종목을 그대로 유지했을 때의 예측
 * @param rebalanced     투자성향 기반 리밸런싱 후의 예측
 * @param shortenedMonths 리밸런싱으로 단축되는 개월 수(현재 도달 개월 - 리밸런싱 도달 개월, 음수면 오히려 늦어짐).
 *                        둘 중 하나라도 30년 안에 도달하지 못하면 null
 */
public record ProjectionDataJson(
        long holdingsAmount,
        long cashAmount,
        PortfolioProjectionDto current,
        PortfolioProjectionDto rebalanced,
        Integer shortenedMonths
) {
}
