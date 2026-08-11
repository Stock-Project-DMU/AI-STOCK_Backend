package com.teamfp.aistock.domain.stock.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.teamfp.aistock.domain.stock.dto.response.HogaResponse;
import com.teamfp.aistock.domain.stock.dto.response.StockPriceResponse;
import com.teamfp.aistock.domain.stock.service.StockService;
import com.teamfp.aistock.global.response.ApiResponse;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/stocks")
@RequiredArgsConstructor
public class StockController {

    private final StockService stockService;

    @GetMapping("/{stockCode}")
    public ApiResponse<StockPriceResponse> getCurrentPrice(@PathVariable String stockCode) {
        return ApiResponse.success(stockService.getCurrentPrice(stockCode));
    }

    @GetMapping("/{stockCode}/hoga")
    public ApiResponse<HogaResponse> getHoga(@PathVariable String stockCode) {
        return ApiResponse.success(stockService.getHoga(stockCode));
    }
}
