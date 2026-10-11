package com.teamfp.aistock.domain.stock.service;

import com.teamfp.aistock.infra.marketdata.*;
import com.teamfp.aistock.infra.marketdata.dto.*;
import com.teamfp.aistock.infra.naver.NaverNewsApiClient;
import com.teamfp.aistock.infra.naver.dto.*;
import com.teamfp.aistock.domain.stock.dto.response.StockSearchSuggestion;
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


    public OverseasIndexDto getExchangeRate() {
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

    public List<StockSearchSuggestion> suggestStocks(String query) {
        if (query == null || query.isBlank() || query.length() > 100) {
            throw new CustomException(ErrorCode.INVALID_INPUT);
        }
        return dartApiClient.searchListedStocks(query, 8).stream()
                .map(stock -> new StockSearchSuggestion(stock.stockCode(), stock.stockName()))
                .toList();
    }

    public List<IndustryPriceDto> getIndexes() {
        return java.util.stream.Stream.of("코스피", "코스닥").map(industryApiClient::getCurrentPrice)
                .flatMap(java.util.Optional::stream).toList();
    }

    public Object getResearch(String stockCode, String section) {
        validateCode(stockCode);
        if (section.equals("analysts")) return investInfoApiClient.getInvestmentOpinions(stockCode);
        if (section.equals("peers")) return highItemApiClient.getTopMarketCap();
        String corpCode = dartApiClient.resolveCorpCodeByStockCode(stockCode)
                .orElseThrow(() -> new CustomException(ErrorCode.STOCK_NOT_FOUND));
        return switch (section) {
            case "finance" -> dartApiClient.getFinancials(new com.teamfp.aistock.infra.dart.dto.DartFinancialRequest(corpCode, java.time.LocalDate.now().getYear() - 1));
            case "earnings" -> dartApiClient.getRecentQuarterlyFinancials(corpCode);
            case "dividend" -> dartApiClient.getDisclosureInfo(corpCode, "배당사항");
            default -> throw new CustomException(ErrorCode.INVALID_INPUT);
        };
    }
    // isAll=true는 홈 주요 종목 무한 스크롤·시뮬레이션 리밸런싱 후보용 — 상위 10건 제한 없이 로컬 시세 데이터의 등록 종목
    // (local-market-data-generator stocks.json, 105개) 전체를 한 번에 정렬해 반환한다. false면 상위 10건.
    public List<RankingItemDto> getRankings(String sort, boolean isAll) {
        if (isAll) {
            int limit = HighItemApiClient.ALL_REGISTERED_STOCKS;
            return switch (sort) {
                case "volume" -> highItemApiClient.getTopVolume(limit);
                case "value" -> highItemApiClient.getTopTradingValue(limit);
                case "rise", "change" -> highItemApiClient.getTopPriceChangeRate(limit);
                case "fall" -> highItemApiClient.getTopPriceDeclineRate(limit);
                case "market-cap" -> highItemApiClient.getTopMarketCap(limit);
                default -> throw new CustomException(ErrorCode.INVALID_INPUT);
            };
        }
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
        validateCode(stockCode);
        if (months < 1 || months > 60) throw new CustomException(ErrorCode.INVALID_INPUT);
        return marketDataApiClient.getHistoricalPrices(stockCode, months);
    }
    // 종목 상세 차트용 — dwmcode(1=일봉/2=주봉/3=월봉)를 그대로 써서 count건(1~60)을 조회한다.
    // getHistory(months)는 months가 오면 월봉으로 바뀌어 일·주 탭에 쓸 수 없어 분리했다.
    public List<HistoricalPriceDto> getChartHistory(String stockCode, int dwmcode, int count) {
        validateCode(stockCode);
        if (dwmcode < 1 || dwmcode > 3 || count < 1 || count > 60) throw new CustomException(ErrorCode.INVALID_INPUT);
        return marketDataApiClient.getChartPrices(stockCode, dwmcode, count);
    }
    public CurrentPriceDetailDto getDetail(String stockCode) {
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
