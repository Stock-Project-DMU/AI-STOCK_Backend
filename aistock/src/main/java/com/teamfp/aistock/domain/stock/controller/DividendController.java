package com.teamfp.aistock.domain.stock.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.teamfp.aistock.domain.stock.dto.response.DividendEntitlementResponse;
import com.teamfp.aistock.domain.stock.dto.response.DividendScheduleResponse;
import com.teamfp.aistock.domain.stock.dto.response.UpcomingDividendResponse;
import com.teamfp.aistock.domain.stock.service.DividendScheduleService;
import com.teamfp.aistock.global.response.ApiResponse;
import com.teamfp.aistock.global.util.SecurityUtil;

import lombok.RequiredArgsConstructor;

/**
 * 배당 조회 API(feature/dividend). 세 API 모두 로그인 사용자만 호출할 수 있다(SecurityConfig의
 * anyRequest().authenticated()).
 */
@RestController
@RequestMapping("/api/dividends")
@RequiredArgsConstructor
public class DividendController {

    private final DividendScheduleService dividendScheduleService;

    /**
     * 배당 스케줄 목록 API — stockCode(종목코드)·year(회계연도, 예: 2026)는 선택 조건
     */
    @GetMapping("/schedule")
    public ApiResponse<List<DividendScheduleResponse>> getSchedules(
            @RequestParam(required = false) String stockCode,
            @RequestParam(required = false) String year
    ) {
        return ApiResponse.success(dividendScheduleService.getSchedules(stockCode, year));
    }

    /**
     * 내 배당 수령 내역 API(지급 완료분, 최신순)
     */
    @GetMapping("/my")
    public ApiResponse<List<DividendEntitlementResponse>> getMyDividends() {
        Long userId = SecurityUtil.getCurrentUserId();
        return ApiResponse.success(dividendScheduleService.getMyDividends(userId));
    }

    /**
     * 내 예정 배당 API(지급 대기 중인 권리 + 보유 종목의 다가오는 배당락)
     */
    @GetMapping("/upcoming")
    public ApiResponse<List<UpcomingDividendResponse>> getUpcomingDividends() {
        Long userId = SecurityUtil.getCurrentUserId();
        return ApiResponse.success(dividendScheduleService.getUpcomingDividends(userId));
    }
}
