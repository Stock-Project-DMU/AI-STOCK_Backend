package com.teamfp.aistock.domain.admin.controller;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.teamfp.aistock.domain.admin.dto.response.AdminTradeResponse;
import com.teamfp.aistock.domain.admin.service.AdminTradeService;
import com.teamfp.aistock.global.response.ApiResponse;

import lombok.RequiredArgsConstructor;

/**
 * 관리자 — 전체 거래(주문) 목록·상세 조회 API. SecurityConfig에서 "/api/admin/**"는
 * hasRole("ADMIN")로 이미 제한되어 있어 여기서는 별도의 권한 검사를 하지 않는다.
 */
@RestController
@RequestMapping("/api/admin/trades")
@RequiredArgsConstructor
public class AdminTradeController {

    private final AdminTradeService adminTradeService;

    @GetMapping
    public ApiResponse<Page<AdminTradeResponse>> getTrades(@PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.success(adminTradeService.getTrades(pageable));
    }

    @GetMapping("/{orderId}")
    public ApiResponse<AdminTradeResponse> getTradeDetail(@PathVariable Long orderId) {
        return ApiResponse.success(adminTradeService.getTradeDetail(orderId));
    }
}
