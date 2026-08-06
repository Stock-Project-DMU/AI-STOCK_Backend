package com.teamfp.aistock.domain.stock.dto.response;

import java.util.List;

import com.teamfp.aistock.domain.stock.dto.HogaDto;

public record HogaResponse(
        String stockCode,
        List<Long> askPrices,
        List<Long> askVolumes,
        List<Long> bidPrices,
        List<Long> bidVolumes
) {

    public static HogaResponse from(HogaDto dto) {
        return new HogaResponse(
                dto.getStockCode(),
                dto.getAskPrices(),
                dto.getAskVolumes(),
                dto.getBidPrices(),
                dto.getBidVolumes()
        );
    }
}
