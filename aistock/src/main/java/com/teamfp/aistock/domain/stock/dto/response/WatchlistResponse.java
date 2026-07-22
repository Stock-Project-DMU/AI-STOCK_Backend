package com.teamfp.aistock.domain.stock.dto.response;

import java.time.LocalDateTime;

import com.teamfp.aistock.domain.stock.entity.Watchlist;

public record WatchlistResponse(
        String stockCode,
        String stockName,
        LocalDateTime addedAt
) {

    public static WatchlistResponse from(Watchlist watchlist) {
        return new WatchlistResponse(
                watchlist.getStockCode(),
                watchlist.getStockName(),
                watchlist.getAddedAt()
        );
    }
}
