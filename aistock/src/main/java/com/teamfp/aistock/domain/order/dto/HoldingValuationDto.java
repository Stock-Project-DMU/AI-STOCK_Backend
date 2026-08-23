package com.teamfp.aistock.domain.order.dto;

import com.teamfp.aistock.domain.order.entity.Holding;

/**
 * HoldingValuationService가 계산한 보유종목 1건의 평가 결과. 호출부(AccountService,
 * OrderService)가 order 도메인의 Holding 엔티티 자체를 직접 들고 다니지 않도록, 필요한 값
 * (종목코드/종목명/수량/평단가/평가용 현재가)만 꺼내 담는다. AccountService.getProfit()과
 * OrderService.getMyHoldings()가 같은 계산 결과를 각자 다른 형태(총 평가금액 합계 vs
 * HoldingResponse 목록)로 가공해 쓴다.
 */
public record HoldingValuationDto(
        Long accountId,
        String stockCode,
        String stockName,
        int quantity,
        long avgPrice,
        long currentPrice
) {

    /**
     * @param holding      평가 대상 보유종목
     * @param currentPrice Redis 시세 캐시에서 조회한 현재가(캐시 미스면 null) — 실제 반영되는
     *                     값은 Holding.resolveValuationPrice()가 null이면 avgPrice로 대체한다.
     */
    public static HoldingValuationDto of(Holding holding, Long currentPrice) {
        long resolvedPrice = holding.resolveValuationPrice(currentPrice);
        return new HoldingValuationDto(
                holding.getAccount().getAccountId(),
                holding.getStockCode(),
                holding.getStockName(),
                holding.getQuantity(),
                holding.getAvgPrice(),
                resolvedPrice
        );
    }
}
