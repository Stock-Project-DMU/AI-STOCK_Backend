package com.teamfp.aistock.domain.stock.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.teamfp.aistock.domain.stock.dto.request.RecentViewedRequest;
import com.teamfp.aistock.domain.stock.dto.response.RecentViewedResponse;
import com.teamfp.aistock.domain.stock.service.RecentViewedService;
import com.teamfp.aistock.global.response.ApiResponse;
import com.teamfp.aistock.global.util.SecurityUtil;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/recent-viewed")
@RequiredArgsConstructor
public class RecentViewedController {

    private final RecentViewedService recentViewedService;

    @GetMapping
    public ApiResponse<List<RecentViewedResponse>> getMyRecentViewed() {
        Long userId = SecurityUtil.getCurrentUserId();
        return ApiResponse.success(recentViewedService.getMyRecentViewed(userId));
    }

    @PostMapping
    public ApiResponse<Void> recordView(@Valid @RequestBody RecentViewedRequest request) {
        Long userId = SecurityUtil.getCurrentUserId();
        recentViewedService.recordView(userId, request.stockCode());
        return ApiResponse.success("최근 본 종목에 기록되었습니다.", null);
    }
}
