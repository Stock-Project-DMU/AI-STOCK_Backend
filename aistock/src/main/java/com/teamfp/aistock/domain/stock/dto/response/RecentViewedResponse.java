package com.teamfp.aistock.domain.stock.dto.response;

import java.time.LocalDateTime;

import com.teamfp.aistock.domain.stock.entity.RecentViewed;

public record RecentViewedResponse(
        String stockCode,
        String stockName,
        LocalDateTime viewedAt
) {

    public static RecentViewedResponse from(RecentViewed recentViewed) {
        return new RecentViewedResponse(
                recentViewed.getStockCode(),
                recentViewed.getStockName(),
                recentViewed.getViewedAt()
        );
    }
}
