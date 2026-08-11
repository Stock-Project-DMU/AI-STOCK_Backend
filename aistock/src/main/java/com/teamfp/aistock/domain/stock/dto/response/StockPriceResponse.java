package com.teamfp.aistock.domain.stock.dto.response;

import com.teamfp.aistock.domain.stock.dto.PriceDirection;
import com.teamfp.aistock.domain.stock.dto.StockPriceDto;

public record StockPriceResponse(
        String stockCode,
        String stockName,
        long currentPrice,
        long changeAmount,
        double changeRate,
        PriceDirection direction,
        long volume
) {

    public static StockPriceResponse from(StockPriceDto dto) {
        return new StockPriceResponse(
                dto.getStockCode(),
                dto.getStockName(),
                dto.getCurrentPrice(),
                dto.getChangeAmount(),
                dto.getChangeRate(),
                PriceDirection.fromChangeRate(dto.getChangeRate()),
                dto.getVolume()
        );
    }
}
