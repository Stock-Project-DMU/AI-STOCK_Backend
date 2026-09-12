package com.teamfp.aistock.domain.order.dto.response;
import java.time.LocalDateTime;
public record RealizedReturnResponse(Long orderId, String stockCode, String stockName, int quantity,
        long averageCost, long sellPrice, long profitAmount, double profitRate, LocalDateTime executedAt) {}
