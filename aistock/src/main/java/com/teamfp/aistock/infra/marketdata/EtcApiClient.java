package com.teamfp.aistock.infra.marketdata;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.teamfp.aistock.infra.marketdata.dto.CurrentPriceDetailDto;
import com.teamfp.aistock.infra.marketdata.dto.NewListingDto;
import com.teamfp.aistock.infra.marketdata.dto.ShortSellingTrendDto;
import com.teamfp.aistock.infra.marketdata.dto.StockCreditInfoDto;
import com.teamfp.aistock.infra.marketdata.dto.StockMasterInfoDto;

import lombok.RequiredArgsConstructor;

/**
 * 신용·대주·담보대출, 신규 상장, 공매도, 종목 기본 정보(AI 재무설계사 상담 도구). local-market-data-generator가 종목 객체의
 * {@code credit}·{@code shortSelling}·{@code master}와 {@code _market.newListings}에 담는 모의 값을 읽는다
 * (fix/local-market-data-stable — 이전에는 외부 시세 데이터 CLNAQ00100/t1411/t1921/t1941/t1403/t1927/t8436을 호출했다).
 * 신용 정보는 이전 응답과 같은 요약 문장(detail)으로 만들어 돌려준다.
 */
@Component
@RequiredArgsConstructor
public class EtcApiClient {

    private static final int MAX_TREND_ITEMS = 5;
    private static final int MAX_LONG_PERIOD_ITEMS = 500;
    private static final int MAX_LISTING_ITEMS = 10;
    private static final int MAX_LONG_PERIOD_LISTING_ITEMS = 50;

    private final LocalMarketDataReader localMarketDataReader;

    /** 담보대출 가능 여부. */
    public Optional<StockCreditInfoDto> getCollateralLoanEligibility(String stockCode) {
        JsonNode credit = localMarketDataReader.getStockField(stockCode, "credit");
        if (!credit.isObject()) {
            return Optional.empty();
        }
        String eligible = credit.path("collateralLoanEligible").asText("");
        return Optional.of(credit(stockCode, "담보융자 가능 여부: %s".formatted(eligible.isBlank() ? "정보없음" : eligible)));
    }

    /** 증거금률. */
    public Optional<StockCreditInfoDto> getMarginRequirement(String stockCode) {
        JsonNode rate = localMarketDataReader.getStockField(stockCode, "credit").path("marginRate");
        if (rate.isMissingNode() || rate.isNull()) {
            return Optional.empty();
        }
        return Optional.of(credit(stockCode, "증거금률 %s%%".formatted(rate.asText())));
    }

    /** 최근 신용융자잔고 비중 추이(최근 {@value #MAX_TREND_ITEMS}일). */
    public Optional<StockCreditInfoDto> getMarginTradingTrend(String stockCode) {
        JsonNode rows = localMarketDataReader.getStockField(stockCode, "credit").path("marginTrend");
        String summary = stream(rows).limit(MAX_TREND_ITEMS)
                .map(row -> "%s: 신용융자잔고비중 %s%%".formatted(row.path("date").asText(), row.path("balanceRatio").asText()))
                .collect(Collectors.joining(", "));
        return summary.isBlank() ? Optional.empty() : Optional.of(credit(stockCode, summary));
    }

    /** 최근 대차거래(공매도 준비 물량) 추이. */
    public Optional<StockCreditInfoDto> getSecuritiesLendingTrend(String stockCode) {
        return getSecuritiesLendingTrend(stockCode, null);
    }

    /**
     * periodMonths가 없으면 최근 {@value #MAX_TREND_ITEMS}일을 나열하고, 있으면 그 기간 안의 합계와 가장 많았던 날을
     * 요약한다(이전 t1941 응답 요약과 같은 문장).
     */
    public Optional<StockCreditInfoDto> getSecuritiesLendingTrend(String stockCode, Integer periodMonths) {
        List<JsonNode> rows = MarketDataPeriod.select(
                stream(localMarketDataReader.getStockField(stockCode, "credit").path("lendingTrend")).toList(),
                row -> row.path("date").asText(null), periodMonths, MAX_TREND_ITEMS, MAX_LONG_PERIOD_ITEMS);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        String summary;
        if (!MarketDataPeriod.isLongPeriod(periodMonths)) {
            summary = rows.stream()
                    .map(row -> "%s: 대차거래량 %s주".formatted(row.path("date").asText(), row.path("volume").asText()))
                    .collect(Collectors.joining(", "));
        } else {
            long total = rows.stream().mapToLong(row -> row.path("volume").asLong()).sum();
            JsonNode peak = rows.stream().max(Comparator.comparingLong(row -> row.path("volume").asLong())).orElseThrow();
            summary = "최근 %d개월(실제 조회된 %d거래일 기준) 누적 대차거래량 %,d주, 가장 많았던 날은 %s(%s주)"
                    .formatted(periodMonths, rows.size(), total, peak.path("date").asText(), peak.path("volume").asText());
        }
        return Optional.of(credit(stockCode, summary));
    }

    /** 최근 신규 상장 종목(최대 {@value #MAX_LISTING_ITEMS}건). */
    public List<NewListingDto> getNewListings() {
        return getNewListings(null);
    }

    /** periodMonths가 있으면 그 기간(최대 24개월) 안의 신규 상장 종목(최대 {@value #MAX_LONG_PERIOD_LISTING_ITEMS}건). */
    public List<NewListingDto> getNewListings(Integer periodMonths) {
        return MarketDataPeriod.select(localMarketDataReader.getMarketList("newListings", NewListingDto.class),
                NewListingDto::getListedDate, periodMonths, MAX_LISTING_ITEMS, MAX_LONG_PERIOD_LISTING_ITEMS);
    }

    /** 최근 {@value #MAX_TREND_ITEMS}거래일 공매도 추이. */
    public List<ShortSellingTrendDto> getRecentShortSellingTrend(String stockCode) {
        return getShortSellingTrend(stockCode, null);
    }

    /** periodMonths가 없으면 최근 {@value #MAX_TREND_ITEMS}거래일, 있으면 그 기간 안의 공매도 추이. */
    public List<ShortSellingTrendDto> getShortSellingTrend(String stockCode, Integer periodMonths) {
        return MarketDataPeriod.select(localMarketDataReader.getStockList(stockCode, "shortSelling", ShortSellingTrendDto.class),
                ShortSellingTrendDto::getDate, periodMonths, MAX_TREND_ITEMS, MAX_LONG_PERIOD_ITEMS);
    }

    /** 종목 기본 정보 — 상·하한가(기준가 ±30%)와 스팩 여부. */
    public Optional<StockMasterInfoDto> getStockMasterInfo(String stockCode) {
        JsonNode master = localMarketDataReader.getStockField(stockCode, "master");
        if (!master.isObject()) {
            return Optional.empty();
        }
        String stockName = localMarketDataReader.getCurrentPrice(stockCode).map(CurrentPriceDetailDto::getStockName).orElse(null);
        return Optional.of(StockMasterInfoDto.builder()
                .stockCode(stockCode)
                .stockName(stockName)
                .upperLimitPrice(master.path("upperLimitPrice").isNumber() ? master.path("upperLimitPrice").asLong() : null)
                .lowerLimitPrice(master.path("lowerLimitPrice").isNumber() ? master.path("lowerLimitPrice").asLong() : null)
                .isSpac(master.path("isSpac").asBoolean(false))
                .build());
    }

    private static StockCreditInfoDto credit(String stockCode, String detail) {
        return StockCreditInfoDto.builder().stockCode(stockCode).detail(detail).build();
    }

    private static java.util.stream.Stream<JsonNode> stream(JsonNode array) {
        List<JsonNode> rows = new ArrayList<>();
        if (array != null && array.isArray()) {
            array.forEach(rows::add);
        }
        return rows.stream();
    }
}
