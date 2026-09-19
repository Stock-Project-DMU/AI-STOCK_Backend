package com.teamfp.aistock.domain.stock.service;

import org.springframework.stereotype.Service;
import com.teamfp.aistock.domain.stock.dto.StockPriceDto;
import com.teamfp.aistock.global.redis.RedisStockCacheService;
import com.teamfp.aistock.infra.ls.LsMarketDataApiClient;
import lombok.RequiredArgsConstructor;

/** Server-verified quote for watchlists and simulated orders when no live tick is cached. */
@Service
@RequiredArgsConstructor
public class StockQuoteService {
    private final RedisStockCacheService redisStockCacheService;
    private final LsMarketDataApiClient lsMarketDataApiClient;

    public StockPriceDto getStockPrice(String stockCode) {
        StockPriceDto cached = redisStockCacheService.getStockPrice(stockCode);
        if (cached != null && cached.getCurrentPrice() > 0
                && cached.getStockName() != null && !cached.getStockName().isBlank()) return cached;
        // Do not publish REST snapshots as live ticks or trigger pending-order execution.
        return lsMarketDataApiClient.getCurrentPrice(stockCode)
                .filter(q -> q.getCurrentPrice() > 0
                        && q.getStockName() != null && !q.getStockName().isBlank())
                .map(q -> StockPriceDto.builder().stockCode(stockCode).stockName(q.getStockName())
                        .currentPrice(q.getCurrentPrice()).changeAmount(q.getChangeAmount())
                        .changeRate(q.getChangeRate()).volume(q.getVolume()).updatedAt(q.getUpdatedAt()).build())
                .orElse(null);
    }
}
