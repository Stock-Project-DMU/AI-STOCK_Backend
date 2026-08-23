package com.teamfp.aistock.domain.order.dto.response;

import com.teamfp.aistock.domain.order.dto.HoldingValuationDto;

/**
 * 보유 종목 목록 조회 응답 DTO. currentPrice는 HoldingValuationDto가 이미 시세 캐시 미스 시
 * avgPrice로 대체를 마친 값이다 — 캐시가 비어있었던 경우 evaluationProfit이 0으로 표시된다.
 */
public record HoldingResponse(
        Long accountId,
        String stockCode,
        String stockName,
        int quantity,
        long avgPrice,
        long currentPrice,
        long evaluationProfit
) {

    public static HoldingResponse of(HoldingValuationDto valuation) {
        long evaluationProfit = (valuation.currentPrice() - valuation.avgPrice()) * valuation.quantity();
        return new HoldingResponse(
                valuation.accountId(),
                valuation.stockCode(),
                valuation.stockName(),
                valuation.quantity(),
                valuation.avgPrice(),
                valuation.currentPrice(),
                evaluationProfit
        );
    }
}
