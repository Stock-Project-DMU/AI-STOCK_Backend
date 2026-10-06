package com.teamfp.aistock.domain.admin.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.teamfp.aistock.domain.stock.dto.response.DividendScheduleReloadResponse;
import com.teamfp.aistock.domain.stock.service.DividendScheduleService;
import com.teamfp.aistock.global.response.ApiResponse;

import lombok.RequiredArgsConstructor;

/**
 * 관리자 — 배당 스케줄 재적재 API(feature/dividend). local-market-data-generator에서 dividends.json을
 * 다시 만든 뒤(배당결정 공시로 금액·지급일이 채워진 경우 등) 서버 재시작 없이 DB에 반영할 때 쓴다.
 * SecurityConfig에서 "/api/admin/**"는 hasRole("ADMIN")로 이미 제한되어 있다.
 */
@RestController
@RequestMapping("/api/admin/dividend")
@RequiredArgsConstructor
public class AdminDividendController {

    private final DividendScheduleService dividendScheduleService;

    @PostMapping("/reload")
    public ApiResponse<DividendScheduleReloadResponse> reloadSchedules() {
        return ApiResponse.success(dividendScheduleService.reloadSchedules());
    }
}
