package com.teamfp.aistock.domain.admin.controller;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.teamfp.aistock.domain.admin.dto.StatisticsInterval;
import com.teamfp.aistock.domain.admin.dto.response.StatisticsPointResponse;
import com.teamfp.aistock.domain.admin.service.AdminStatisticsService;
import com.teamfp.aistock.global.response.ApiResponse;

import lombok.RequiredArgsConstructor;

/**
 * 관리자 — 기간별 통계 API(ADMIN_API_BACKEND_HANDOFF.md 6.1). from/to는 날짜(자정 기준)로 받아
 * from은 00:00:00, to는 23:59:59.999999999까지 포함하도록 하루 끝 시각으로 넓혀 서비스에 넘긴다
 * — 사용자가 "8/31까지"라고 요청했을 때 8/31 하루 전체가 빠짐없이 포함되어야 하기 때문이다.
 * SecurityConfig에서 "/api/admin/**"는 hasRole("ADMIN")로 이미 제한되어 있어 여기서는 별도의
 * 권한 검사를 하지 않는다.
 */
@RestController
@RequestMapping("/api/admin/statistics")
@RequiredArgsConstructor
public class AdminStatisticsController {

    private final AdminStatisticsService adminStatisticsService;

    @GetMapping("/users")
    public ApiResponse<List<StatisticsPointResponse>> getUserStatistics(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "DAY") StatisticsInterval interval
    ) {
        return ApiResponse.success(adminStatisticsService.getUserStatistics(startOfDay(from), endOfDay(to), interval));
    }

    @GetMapping("/orders")
    public ApiResponse<List<StatisticsPointResponse>> getOrderStatistics(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "DAY") StatisticsInterval interval
    ) {
        return ApiResponse.success(adminStatisticsService.getOrderStatistics(startOfDay(from), endOfDay(to), interval));
    }

    @GetMapping("/amounts")
    public ApiResponse<List<StatisticsPointResponse>> getAmountStatistics(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "DAY") StatisticsInterval interval
    ) {
        return ApiResponse.success(adminStatisticsService.getAmountStatistics(startOfDay(from), endOfDay(to), interval));
    }

    private LocalDateTime startOfDay(LocalDate date) {
        return date.atStartOfDay();
    }

    private LocalDateTime endOfDay(LocalDate date) {
        return date.atTime(LocalTime.MAX);
    }
}
