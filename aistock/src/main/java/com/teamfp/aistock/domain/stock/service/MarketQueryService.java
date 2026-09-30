package com.teamfp.aistock.domain.stock.service;

import com.teamfp.aistock.infra.marketdata.*;
import com.teamfp.aistock.infra.marketdata.dto.*;
import com.teamfp.aistock.infra.naver.NaverNewsApiClient;
import com.teamfp.aistock.infra.naver.dto.*;
import com.teamfp.aistock.global.exception.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.List;

@Service @RequiredArgsConstructor
public class MarketQueryService {
    private final HighItemApiClient highItemApiClient;
    private final MarketDataApiClient marketDataApiClient;
    private final NaverNewsApiClient naverNewsApiClient;
    private final IndustryApiClient industryApiClient;
    private final InvestInfoApiClient investInfoApiClient;
    private final com.teamfp.aistock.infra.dart.DartApiClient dartApiClient;
    @org.springframework.beans.factory.annotation.Value("${market-data.app-key:}") private String appKey;
    @org.springframework.beans.factory.annotation.Value("${market-data.app-secret:}") private String appSecret;

    private void requireMarketConfigured() {
        if (appKey == null || appKey.isBlank() || appSecret == null || appSecret.isBlank())
            throw new CustomException(ErrorCode.MARKET_NOT_CONFIGURED);
    }

    public OverseasIndexDto getExchangeRate() {
        requireMarketConfigured();
        return investInfoApiClient.getOverseasIndex("R", "USDKRWSMBS")
                .orElseThrow(() -> new CustomException(ErrorCode.STOCK_PRICE_NOT_AVAILABLE));
    }

    public String searchStock(String query) {
        if (query == null || query.isBlank() || query.length() > 100) throw new CustomException(ErrorCode.INVALID_INPUT);
        String value = query.trim();
        if (value.matches("[0-9]{6}")) return value;
        return dartApiClient.resolveStockCodeByName(value)
                .orElseThrow(() -> new CustomException(ErrorCode.STOCK_NOT_FOUND));
    }

    public List<IndustryPriceDto> getIndexes() {
        requireMarketConfigured();
        return java.util.stream.Stream.of("코스피", "코스닥").map(industryApiClient::getCurrentPrice)
                .flatMap(java.util.Optional::stream).toList();
    }

    public Object getResearch(String stockCode, String section) {
        requireMarketConfigured();
        validateCode(stockCode);
        if (section.equals("analysts")) return investInfoApiClient.getInvestmentOpinions(stockCode);
        if (section.equals("peers")) return highItemApiClient.getTopMarketCap();
        String corpCode = dartApiClient.resolveCorpCodeByName(getDetail(stockCode).getStockName())
                .orElseThrow(() -> new CustomException(ErrorCode.STOCK_NOT_FOUND));
        return switch (section) {
            case "finance" -> dartApiClient.getFinancials(new com.teamfp.aistock.infra.dart.dto.DartFinancialRequest(corpCode, java.time.LocalDate.now().getYear() - 1));
            case "earnings" -> dartApiClient.getRecentQuarterlyFinancials(corpCode);
            case "dividend" -> dartApiClient.getDisclosureInfo(corpCode, "배당사항");
            default -> throw new CustomException(ErrorCode.INVALID_INPUT);
        };
    }
    public List<RankingItemDto> getRankings(String sort) {
        requireMarketConfigured();
        return switch (sort) {
            case "volume" -> highItemApiClient.getTopVolume();
            case "value" -> highItemApiClient.getTopTradingValue();
            // 상승·하락 순위는 코스피+코스닥 전체 시장 기준(#13). "change"는 기존 호출 호환용 상승 순위 별칭.
            case "rise", "change" -> highItemApiClient.getTopPriceChangeRate();
            case "fall" -> highItemApiClient.getTopPriceDeclineRate();
            case "market-cap" -> highItemApiClient.getTopMarketCap();
            default -> throw new CustomException(ErrorCode.INVALID_INPUT);
        };
    }
    public List<HistoricalPriceDto> getHistory(String stockCode, int months) {
        requireMarketConfigured();
        validateCode(stockCode);
        if (months < 1 || months > 60) throw new CustomException(ErrorCode.INVALID_INPUT);
        return marketDataApiClient.getHistoricalPrices(stockCode, months);
    }
    public CurrentPriceDetailDto getDetail(String stockCode) {
        requireMarketConfigured();
        validateCode(stockCode);
        return marketDataApiClient.getCurrentPrice(stockCode)
                .orElseThrow(() -> new CustomException(ErrorCode.STOCK_PRICE_NOT_AVAILABLE));
    }
    public NaverNewsSearchResponse getNews(String query) {
        if (query == null || query.isBlank() || query.length() > 100) throw new CustomException(ErrorCode.INVALID_INPUT);
        return naverNewsApiClient.search(new NaverNewsSearchRequest(query.trim(), null, 30));
    }
    private void validateCode(String stockCode) {
        if (!stockCode.matches("[0-9]{6}")) throw new CustomException(ErrorCode.INVALID_INPUT);
    }
}
