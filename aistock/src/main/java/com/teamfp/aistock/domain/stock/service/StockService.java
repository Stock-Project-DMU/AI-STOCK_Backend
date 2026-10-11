package com.teamfp.aistock.domain.stock.service;

import org.springframework.stereotype.Service;

import com.teamfp.aistock.domain.stock.dto.PriceDirection;
import com.teamfp.aistock.domain.stock.dto.response.HogaResponse;
import com.teamfp.aistock.domain.stock.dto.response.StockPriceResponse;
import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;
import com.teamfp.aistock.infra.marketdata.LocalMarketDataReader;
import com.teamfp.aistock.infra.marketdata.dto.CurrentPriceDetailDto;
import com.teamfp.aistock.infra.marketdata.dto.HogaData;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class StockService {

    private final LocalMarketDataReader localMarketDataReader;

    /**
     * 현재가 조회 — local-market-data-generator가 갱신하는 market_data.json을 읽는다(fix/local-market-data-stable에서
     * 외부 시세 데이터 WebSocket(real 모드)이 Redis에 채우던 캐시 경로를 없앴다). 종목이 없으면 종목 자체가 없는 게 아니라
     * 시세를 일시적으로 못 가져오는 상황으로 보고 STOCK_PRICE_NOT_AVAILABLE을 던진다(OrderService.createMarketOrder()와
     * 동일한 판단, NAMING.md 8-4 참고).
     */
    public StockPriceResponse getCurrentPrice(String stockCode) {
        CurrentPriceDetailDto local = localMarketDataReader.getCurrentPrice(stockCode)
                .orElseThrow(() -> new CustomException(ErrorCode.STOCK_PRICE_NOT_AVAILABLE));
        return toStockPriceResponse(local);
    }

    private StockPriceResponse toStockPriceResponse(CurrentPriceDetailDto dto) {
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

    /** 호가 조회 — market_data.json의 호가 필드(askPrices 등). 없으면 getCurrentPrice()와 같은 이유로 STOCK_PRICE_NOT_AVAILABLE. */
    public HogaResponse getHoga(String stockCode) {
        HogaData local = localMarketDataReader.getHoga(stockCode)
                .orElseThrow(() -> new CustomException(ErrorCode.STOCK_PRICE_NOT_AVAILABLE));
        return toHogaResponse(local);
    }

    private HogaResponse toHogaResponse(HogaData dto) {
        return new HogaResponse(
                dto.getStockCode(),
                dto.getAskPrices(),
                dto.getAskVolumes(),
                dto.getBidPrices(),
                dto.getBidVolumes()
        );
    }
}
