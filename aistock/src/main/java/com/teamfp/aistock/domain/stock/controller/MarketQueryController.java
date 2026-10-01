package com.teamfp.aistock.domain.stock.controller;

import com.teamfp.aistock.domain.stock.service.MarketQueryService;
import com.teamfp.aistock.infra.marketdata.dto.*;
import com.teamfp.aistock.domain.stock.dto.response.StockSearchSuggestion;
import com.teamfp.aistock.infra.naver.dto.NaverNewsSearchResponse;
import com.teamfp.aistock.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @RequestMapping("/api/market") @RequiredArgsConstructor
public class MarketQueryController {
    private final MarketQueryService marketQueryService;
    @GetMapping("/exchange-rate") public ApiResponse<OverseasIndexDto> getExchangeRate() {
        return ApiResponse.success(marketQueryService.getExchangeRate());
    }
    @GetMapping("/search") public ApiResponse<String> searchStock(@RequestParam String query) {
        return ApiResponse.success(marketQueryService.searchStock(query));
    }
    @GetMapping("/search/suggestions") public ApiResponse<List<StockSearchSuggestion>> suggestStocks(@RequestParam String query) {
        return ApiResponse.success(marketQueryService.suggestStocks(query));
    }
    @GetMapping("/indexes") public ApiResponse<List<IndustryPriceDto>> getIndexes() {
        return ApiResponse.success(marketQueryService.getIndexes());
    }
    @GetMapping("/stocks/{stockCode}/research") public ApiResponse<Object> getResearch(@PathVariable String stockCode, @RequestParam String section) {
        return ApiResponse.success(marketQueryService.getResearch(stockCode, section));
    }
    @GetMapping("/rankings") public ApiResponse<List<RankingItemDto>> getRankings(@RequestParam(defaultValue = "volume") String sort,
            @RequestParam(name = "all", defaultValue = "false") boolean isAll) {
        return ApiResponse.success(marketQueryService.getRankings(sort, isAll));
    }
    @GetMapping("/stocks/{stockCode}/history") public ApiResponse<List<HistoricalPriceDto>> getHistory(@PathVariable String stockCode, @RequestParam(defaultValue = "12") int months) {
        return ApiResponse.success(marketQueryService.getHistory(stockCode, months));
    }
    @GetMapping("/stocks/{stockCode}/detail") public ApiResponse<CurrentPriceDetailDto> getDetail(@PathVariable String stockCode) {
        return ApiResponse.success(marketQueryService.getDetail(stockCode));
    }
    @GetMapping("/news") public ApiResponse<NaverNewsSearchResponse> getNews(@RequestParam(defaultValue = "코스피") String query) {
        return ApiResponse.success(marketQueryService.getNews(query));
    }
}
