package com.teamfp.aistock.infra.marketdata;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

import com.teamfp.aistock.infra.marketdata.dto.FinancialRankingDto;
import com.teamfp.aistock.infra.marketdata.dto.InvestmentOpinionDto;
import com.teamfp.aistock.infra.marketdata.dto.MarketLiquidityDto;
import com.teamfp.aistock.infra.marketdata.dto.OverseasIndexDto;
import com.teamfp.aistock.infra.marketdata.dto.ShareholderMeetingDto;

import lombok.RequiredArgsConstructor;

/**
 * 투자정보 — 애널리스트 투자의견, 주주총회 일정, 재무순위, 해외지수·환율, 증시 주변 자금(종목 리서치 애널리스트 탭·홈 환율·
 * AI 재무설계사 상담 도구). local-market-data-generator가 종목 객체의 {@code opinions}·{@code shareholderMeetings}와
 * {@code _market.financialRanking}·{@code _market.overseasIndexes}·{@code _market.marketLiquidity}에 담는 모의 값을 읽는다
 * (fix/local-market-data-stable — 이전 real 모드에서는 외부 시세 데이터 t3401/t3202/t3341/t3521/t8428을 호출했다).
 * 투자의견만 예외로, 생성기의 opinion_collector.py가 LS증권 t3401에서 받아 둔 스냅샷이 있으면 실제 값이다
 * ({@link #getInvestmentOpinionCollectedAt(String)}, fix/stock-detail-review). 나머지는 모두 실제 데이터가 아니다.
 */
@Component
@RequiredArgsConstructor
public class InvestInfoApiClient {

    private static final int MAX_OPINION_ITEMS = 5;
    private static final int MAX_SCHEDULE_ITEMS = 5;
    private static final int MAX_FINANCIAL_RANKING_ITEMS = 10;
    private static final int MAX_LIQUIDITY_ITEMS = 5;
    private static final int MAX_LONG_PERIOD_ITEMS = 500;
    private static final String OPINION_COLLECTED_AT_FIELD = "opinionsCollectedAt";
    // 투자의견 정리(2026-10-12) — 리포트가 뜸한 종목은 10년 넘은 의견이 "최신"으로 나와서 최근 1년 것만 쓴다.
    static final int OPINION_RECENT_DAYS = 365;
    // LS 원본에 0이 하나 빠지거나 더 붙은 목표주가가 섞여 있다(예: 30만 원 → 3만 원). 기준 주가의 이 범위를 벗어나면 원본 오기로 보고 뺀다.
    static final double TARGET_PRICE_MIN_RATIO = 0.2;
    static final double TARGET_PRICE_MAX_RATIO = 5.0;
    private static final DateTimeFormatter OPINION_DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;

    private final LocalMarketDataReader localMarketDataReader;
    // "최근 1년" 기준일 — 테스트에서 날짜를 고정할 수 있게 둔다.
    private Clock clock = Clock.system(ZoneId.of("Asia/Seoul"));

    /**
     * 증권사별 투자의견·목표주가 변경 이력(최신 {@value #MAX_OPINION_ITEMS}건). 최근 {@value #OPINION_RECENT_DAYS}일 안의 의견만, 목표주가가
     * 기준 주가(발표일 종가, 없으면 현재가)의 {@value #TARGET_PRICE_MIN_RATIO}~{@value #TARGET_PRICE_MAX_RATIO}배를 벗어나는 줄은 뺀다.
     * ETF나 데이터 없는 종목은 빈 목록.
     */
    public List<InvestmentOpinionDto> getInvestmentOpinions(String stockCode) {
        LocalDate oldest = LocalDate.now(clock).minusDays(OPINION_RECENT_DAYS);
        Long currentPrice = localMarketDataReader.getCurrentPrice(stockCode).map(price -> price.getCurrentPrice()).orElse(null);
        return localMarketDataReader.getStockList(stockCode, "opinions", InvestmentOpinionDto.class).stream()
                .filter(item -> isRecent(item, oldest))
                .filter(item -> hasPlausibleTargetPrice(item, currentPrice))
                .limit(MAX_OPINION_ITEMS)
                .toList();
    }

    private static boolean isRecent(InvestmentOpinionDto item, LocalDate oldest) {
        try {
            return item.getDate() != null && !LocalDate.parse(item.getDate(), OPINION_DATE_FORMAT).isBefore(oldest);
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static boolean hasPlausibleTargetPrice(InvestmentOpinionDto item, Long currentPrice) {
        Long target = item.getTargetPriceAfter();
        Long base = item.getClosePriceOnDate() != null && item.getClosePriceOnDate() > 0 ? item.getClosePriceOnDate() : currentPrice;
        if (target == null || target <= 0 || base == null || base <= 0) {
            return true; // 목표주가가 없거나 비교할 주가가 없으면 걸러낼 근거가 없다
        }
        double ratio = (double) target / base;
        return ratio >= TARGET_PRICE_MIN_RATIO && ratio <= TARGET_PRICE_MAX_RATIO;
    }

    /**
     * 투자의견이 실제 데이터면 그 수집일(YYYY-MM-DD) — local-market-data-generator의 opinion_collector.py가 LS증권 투자의견(t3401)을
     * 받아 둔 종목은 market_data.json 종목 객체에 {@code opinionsCollectedAt}이 붙는다. 빈 값이면 생성기가 만든 모의 의견이다
     * (fix/stock-detail-review).
     */
    public Optional<String> getInvestmentOpinionCollectedAt(String stockCode) {
        JsonNode collectedAt = localMarketDataReader.getStockField(stockCode, OPINION_COLLECTED_AT_FIELD);
        return collectedAt.isTextual() && !collectedAt.asText().isBlank() ? Optional.of(collectedAt.asText()) : Optional.empty();
    }

    /** 주주총회 등 증시 일정(최대 {@value #MAX_SCHEDULE_ITEMS}건). */
    public List<ShareholderMeetingDto> getShareholderMeetingSchedule(String stockCode) {
        List<ShareholderMeetingDto> items =
                localMarketDataReader.getStockList(stockCode, "shareholderMeetings", ShareholderMeetingDto.class);
        return items.size() > MAX_SCHEDULE_ITEMS ? items.subList(0, MAX_SCHEDULE_ITEMS) : items;
    }

    /**
     * 재무순위(최대 {@value #MAX_FINANCIAL_RANKING_ITEMS}건). criteria는 이전 t3341의 gubun1 코드(1=매출증가율,
     * 2=영업이익증가율, 4=부채비율, 6=EPS, 7=BPS, 8=ROE, 9=PER, a=PBR, b=PEG — AiPlanningService가 넘긴다).
     * 모르는 코드는 빈 목록.
     */
    public List<FinancialRankingDto> getFinancialRanking(String criteria) {
        List<FinancialRankingDto> items = localMarketDataReader.convertList(
                localMarketDataReader.getMarketField("financialRanking").path(criteria == null ? "" : criteria),
                FinancialRankingDto.class);
        return items.size() > MAX_FINANCIAL_RANKING_ITEMS ? items.subList(0, MAX_FINANCIAL_RANKING_ITEMS) : items;
    }

    /**
     * 해외지수·환율. kind는 이전 t3521 구분값(S=지수, R=환율, F=선물)으로, 생성기 데이터는 심볼만으로 찾으므로 쓰지 않는다.
     * 생성기가 만드는 심볼은 DJI@DJI(다우), NAS@IXIC(나스닥), USDKRWSMBS(원/달러), NYM@CL(WTI) — 그 외는 빈 값.
     */
    public Optional<OverseasIndexDto> getOverseasIndex(String kind, String symbol) {
        if (symbol == null) {
            return Optional.empty();
        }
        return localMarketDataReader.convert(
                localMarketDataReader.getMarketField("overseasIndexes").path(symbol), OverseasIndexDto.class);
    }

    /** 최근 {@value #MAX_LIQUIDITY_ITEMS}거래일 고객예탁금·신용융자잔고(백만원). */
    public List<MarketLiquidityDto> getRecentMarketLiquidityTrend() {
        return getMarketLiquidityTrend(null);
    }

    /** periodMonths가 없으면 최근 {@value #MAX_LIQUIDITY_ITEMS}거래일, 있으면 그 기간 안의 거래일(로컬 데이터에 있는 만큼). */
    public List<MarketLiquidityDto> getMarketLiquidityTrend(Integer periodMonths) {
        return MarketDataPeriod.select(localMarketDataReader.getMarketList("marketLiquidity", MarketLiquidityDto.class),
                MarketLiquidityDto::getDate, periodMonths, MAX_LIQUIDITY_ITEMS, MAX_LONG_PERIOD_ITEMS);
    }
}
