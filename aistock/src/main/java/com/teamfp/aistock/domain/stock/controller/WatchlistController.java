package com.teamfp.aistock.domain.stock.controller;

import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.teamfp.aistock.domain.stock.dto.request.WatchlistRequest;
import com.teamfp.aistock.domain.stock.dto.response.WatchlistResponse;
import com.teamfp.aistock.domain.stock.service.WatchlistService;
import com.teamfp.aistock.global.response.ApiResponse;
import com.teamfp.aistock.global.util.SecurityUtil;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/watchlist")
@RequiredArgsConstructor
public class WatchlistController {

    private final WatchlistService watchlistService;

    @GetMapping
    public ApiResponse<List<WatchlistResponse>> getMyWatchlist() {
        Long userId = SecurityUtil.getCurrentUserId();
        return ApiResponse.success(watchlistService.getMyWatchlist(userId));
    }

    @PostMapping
    public ApiResponse<Void> addWatchlist(@Valid @RequestBody WatchlistRequest request) {
        Long userId = SecurityUtil.getCurrentUserId();
        watchlistService.addWatchlist(userId, request.stockCode());
        return ApiResponse.success("관심종목에 추가되었습니다.", null);
    }

    @DeleteMapping("/{stockCode}")
    public ApiResponse<Void> removeWatchlist(@PathVariable String stockCode) {
        Long userId = SecurityUtil.getCurrentUserId();
        watchlistService.removeWatchlist(userId, stockCode);
        return ApiResponse.success("관심종목에서 삭제되었습니다.", null);
    }
}
