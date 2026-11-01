package com.teamfp.aistock.domain.stock.service;

import org.springframework.stereotype.Service;
import com.teamfp.aistock.domain.stock.dto.StockPriceDto;
import com.teamfp.aistock.global.redis.RedisStockCacheService;
import com.teamfp.aistock.infra.marketdata.MarketDataApiClient;
import lombok.RequiredArgsConstructor;

/** Server-verified quote for watchlists and simulated orders when no live tick is cached. */
@Service
@RequiredArgsConstructor
public class StockQuoteService {
    private final RedisStockCacheService redisStockCacheService;
    private final MarketDataApiClient marketDataApiClient;

    public StockPriceDto getStockPrice(String stockCode) {
        StockPriceDto cached = redisStockCacheService.getStockPrice(stockCode);
        if (cached != null && cached.getCurrentPrice() > 0
                && cached.getStockName() != null && !cached.getStockName().isBlank()) return cached;
        // Do not publish REST snapshots as live ticks or trigger pending-order execution.
        return marketDataApiClient.getCurrentPrice(stockCode)
                .filter(q -> q.getCurrentPrice() > 0
                        && q.getStockName() != null && !q.getStockName().isBlank())
                .map(q -> StockPriceDto.builder().stockCode(stockCode).stockName(q.getStockName())
                        .currentPrice(q.getCurrentPrice()).changeAmount(q.getChangeAmount())
                        .changeRate(q.getChangeRate()).volume(q.getVolume()).updatedAt(q.getUpdatedAt()).build())
                .orElse(null);
    }

    /**
     * getStockPrice()가 null일 때 원인 구분용 — 시세 원본에 없는 종목이면 true(STOCK_NOT_FOUND, 404), 원본을 못 읽었거나
     * 종목은 있는데 시세가 유효하지 않으면 false(STOCK_PRICE_NOT_AVAILABLE, 503). StockService.getCurrentPrice()와 같은 기준이다.
     */
    public boolean isUnlistedStock(String stockCode) {
        return marketDataApiClient.isUnlistedStock(stockCode);
    }
}
