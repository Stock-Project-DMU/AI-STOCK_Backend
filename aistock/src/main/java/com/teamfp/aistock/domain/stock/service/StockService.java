package com.teamfp.aistock.domain.stock.service;

import org.springframework.stereotype.Service;

import com.teamfp.aistock.domain.stock.dto.HogaDto;
import com.teamfp.aistock.domain.stock.dto.StockPriceDto;
import com.teamfp.aistock.domain.stock.dto.response.HogaResponse;
import com.teamfp.aistock.domain.stock.dto.response.StockPriceResponse;
import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;
import com.teamfp.aistock.global.redis.RedisStockCacheService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class StockService {

    private final RedisStockCacheService redisStockCacheService;

    /**
     * 현재가 조회. Redis 캐시(TTL 5초)에서 읽으며, 캐시가 비어있으면(최근 tick이 없으면)
     * 종목 자체가 없는 게 아니라 시세를 일시적으로 못 가져오는 상황이므로 STOCK_NOT_FOUND가
     * 아닌 STOCK_PRICE_NOT_AVAILABLE을 던진다(OrderService.createMarketOrder()와 동일한 판단,
     * NAMING.md 8-4 참고). 종목코드 자체의 유효성은 별도 종목 마스터가 없어(KNOWN_ISSUES.md 1번)
     * 이 브랜치에서는 검증하지 않는다.
     */
    public StockPriceResponse getCurrentPrice(String stockCode) {
        StockPriceDto dto = redisStockCacheService.getStockPrice(stockCode);
        if (dto == null) {
            throw new CustomException(ErrorCode.STOCK_PRICE_NOT_AVAILABLE);
        }
        return StockPriceResponse.from(dto);
    }

    /**
     * 호가 조회. getCurrentPrice()와 동일한 이유로 캐시 미스는 STOCK_PRICE_NOT_AVAILABLE로 처리한다.
     */
    public HogaResponse getHoga(String stockCode) {
        HogaDto dto = redisStockCacheService.getHogaData(stockCode);
        if (dto == null) {
            throw new CustomException(ErrorCode.STOCK_PRICE_NOT_AVAILABLE);
        }
        return HogaResponse.from(dto);
    }
}
