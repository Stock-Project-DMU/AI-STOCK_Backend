package com.teamfp.aistock.domain.stock.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.teamfp.aistock.domain.stock.dto.response.MarketStatusResponse;
import com.teamfp.aistock.domain.stock.service.MarketSessionService;
import com.teamfp.aistock.global.response.ApiResponse;

import lombok.RequiredArgsConstructor;

/** 장 운영 상태(실시세 전환 2단계) — 화면의 장중/장 마감/휴장 표시와 장외 주문 버튼 비활성화에 쓴다. */
@RestController
@RequestMapping("/api/market")
@RequiredArgsConstructor
public class MarketSessionController {

    private final MarketSessionService marketSessionService;

    @GetMapping("/status")
    public ApiResponse<MarketStatusResponse> getStatus() {
        return ApiResponse.success(marketSessionService.getStatus());
    }
}
