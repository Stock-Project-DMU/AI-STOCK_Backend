package com.teamfp.aistock.domain.stock.service;

import java.util.Map;

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
     * 현재가 조회 — local-market-data-generator가 갱신하는 market_data.json을 읽는다. 시세 데이터는 정상으로 읽혔는데 그 안에
     * 종목코드가 없으면 등록되지 않은 종목이므로 STOCK_NOT_FOUND(404), 시세 데이터 자체를 못 읽었으면(파일 없음·생성기 중단)
     * 시세를 일시적으로 못 가져오는 상황으로 보고 STOCK_PRICE_NOT_AVAILABLE(503)을 던진다(NAMING.md 8-4 참고).
     */
    public StockPriceResponse getCurrentPrice(String stockCode) {
        CurrentPriceDetailDto local = localMarketDataReader.getCurrentPrice(stockCode)
                .orElseThrow(() -> missingPriceException(stockCode));
        return toStockPriceResponse(local);
    }

    // 원본이 비어 있으면 일시 장애(503), 원본에 다른 종목은 있는데 이 종목만 없으면 없는 종목(404).
    private CustomException missingPriceException(String stockCode) {
        Map<String, CurrentPriceDetailDto> currentPrices = localMarketDataReader.getAllCurrentPrices();
        if (!currentPrices.isEmpty() && !currentPrices.containsKey(stockCode)) {
            return new CustomException(ErrorCode.STOCK_NOT_FOUND);
        }
        return new CustomException(ErrorCode.STOCK_PRICE_NOT_AVAILABLE);
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

    /**
     * 호가 조회 — market_data.json의 호가 필드(askPrices 등). 등록되지 않은 종목이면 STOCK_NOT_FOUND, 종목은 있는데 호가가
     * 비어 있거나 원본을 못 읽었으면 STOCK_PRICE_NOT_AVAILABLE(getCurrentPrice()와 같은 판단).
     */
    public HogaResponse getHoga(String stockCode) {
        HogaData local = localMarketDataReader.getHoga(stockCode)
                .orElseThrow(() -> missingPriceException(stockCode));
        return toHogaResponse(local);
    }

    private HogaResponse toHogaResponse(HogaData dto) {
        return new HogaResponse(
                dto.getStockCode(),
                dto.getAskPrices(),
                dto.getAskVolumes(),
                dto.getBidPrices(),
                dto.getBidVolumes(),
                dto.getTotalAskVolume(),
                dto.getTotalBidVolume()
        );
    }
}
