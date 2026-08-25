package com.teamfp.aistock.infra.ls;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.infra.ls.dto.LsFinancialRankingDto;
import com.teamfp.aistock.infra.ls.dto.LsInvestmentOpinionDto;
import com.teamfp.aistock.infra.ls.dto.LsMarketLiquidityDto;
import com.teamfp.aistock.infra.ls.dto.LsOverseasIndexDto;
import com.teamfp.aistock.infra.ls.dto.LsShareholderMeetingDto;

import lombok.extern.slf4j.Slf4j;

/**
 * LS증권 Open API [주식] 투자정보 카테고리({@code /stock/investinfo})를 조회하는 클라이언트.
 * 투자의견(t3401), 종목별증시일정(t3202, 그중 주주총회만), 재무순위종합(t3341, 2026-08-11
 * 추가), 해외지수조회API용(t3521, 2026-08-11 추가), 증시주변자금추이(t8428, 2026-08-11 추가)
 * 5개 TR을 다룬다. 같은 URL·인증을 공유하는 TR이라 {@link LsMarketDataApiClient}/
 * {@link LsInvestorTrendApiClient}와 같은 패턴으로 별도 클라이언트를 둔다.
 */
@Slf4j
@Component
public class LsInvestInfoApiClient extends LsApiClientSupport {

    private static final String INVESTMENT_OPINION_TR_CD = "t3401";
    private static final String OPINION_OUT_BLOCK_KEY = "t3401OutBlock1";
    private static final int MAX_OPINION_ITEMS = 5;

    private static final String SCHEDULE_TR_CD = "t3202";
    private static final String SCHEDULE_OUT_BLOCK_KEY = "t3202OutBlock";
    // t3202의 upgu(업무구분) 14종 중 "09"(주주총회)만 남긴다 — 나머지(유상증자/무상증자/배당/
    // 감자/합병분할 등)는 이미 DART 정기·주요사항보고서 도구가 담당하고 있어, 그대로 다 넘기면
    // 같은 주제를 두 도구가 동시에 답할 후보가 되어 Gemini가 헷갈릴 수 있다(LsShareholderMeetingDto
    // 클래스 주석 참고).
    private static final String SHAREHOLDER_MEETING_UPGU = "09";
    private static final int MAX_SCHEDULE_ITEMS = 5;

    private final LsAccessTokenProvider accessTokenProvider;

    @Value("${ls.investinfo-url}")
    private String investInfoUrl;

    public LsInvestInfoApiClient(LsAccessTokenProvider accessTokenProvider, RestClient.Builder restClientBuilder) {
        super(restClientBuilder);
        this.accessTokenProvider = accessTokenProvider;
    }

    /**
     * 종목코드로 증권사별 투자의견/목표주가 변경 이력(최신 5건)을 조회한다. 실패하거나 데이터가
     * 없으면 빈 리스트를 반환한다.
     */
    public List<LsInvestmentOpinionDto> getInvestmentOpinions(String stockCode) {
        try {
            String token = accessTokenProvider.issueAccessToken();
            Map<String, Object> inBlock = new java.util.LinkedHashMap<>();
            inBlock.put("shcode", stockCode);
            inBlock.put("gubun1", "");
            inBlock.put("tradno", "");
            inBlock.put("cts_date", "");
            Map<String, Object> requestBody = Map.of("t3401InBlock", inBlock);

            Map<String, Object> response = call(investInfoUrl, INVESTMENT_OPINION_TR_CD, requestBody, token, "LS 투자의견 조회 실패");

            return parseOpinions(stockCode, response);
        } catch (CustomException e) {
            log.warn("LS 투자의견 조회 중 오류 - stockCode: {}, 사유: {}", stockCode, e.getMessage());
            return List.of();
        }
    }

    /**
     * 종목코드로 주주총회 일정(전체 이력 중 upgu=09만 필터링, 최신 5건)을 조회한다. 실패하거나
     * 데이터가 없으면 빈 리스트를 반환한다.
     */
    public List<LsShareholderMeetingDto> getShareholderMeetingSchedule(String stockCode) {
        try {
            String token = accessTokenProvider.issueAccessToken();
            Map<String, Object> inBlock = new java.util.LinkedHashMap<>();
            inBlock.put("shcode", stockCode);
            inBlock.put("date", "");
            Map<String, Object> requestBody = Map.of("t3202InBlock", inBlock);

            Map<String, Object> response = call(investInfoUrl, SCHEDULE_TR_CD, requestBody, token, "LS 증시일정 조회 실패");

            return parseShareholderMeetings(stockCode, response);
        } catch (CustomException e) {
            log.warn("LS 증시일정 조회 중 오류 - stockCode: {}, 사유: {}", stockCode, e.getMessage());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private List<LsInvestmentOpinionDto> parseOpinions(String stockCode, Map<String, Object> response) {
        if (response == null) {
            return List.of();
        }
        Object outBlockObj = response.get(OPINION_OUT_BLOCK_KEY);
        if (!(outBlockObj instanceof List)) {
            log.warn("LS 투자의견 응답에서 {}을 찾지 못함 - stockCode: {}, 응답: {}", OPINION_OUT_BLOCK_KEY, stockCode, response);
            return List.of();
        }
        List<Map<String, Object>> outBlock = (List<Map<String, Object>>) outBlockObj;

        List<LsInvestmentOpinionDto> items = outBlock.stream()
                .map(row -> LsInvestmentOpinionDto.builder()
                        .date(stringOf(row.get("date")))
                        .securitiesFirm(stringOf(row.get("tradname")))
                        .opinionBefore(stringOf(row.get("nopn")))
                        .opinionAfter(stringOf(row.get("bopn")))
                        .targetPriceBefore(parseLong(row.get("boga")))
                        .targetPriceAfter(parseLong(row.get("noga")))
                        .closePriceOnDate(parseLong(row.get("close")))
                        .build())
                .toList();
        return items.size() > MAX_OPINION_ITEMS ? items.subList(0, MAX_OPINION_ITEMS) : items;
    }

    @SuppressWarnings("unchecked")
    private List<LsShareholderMeetingDto> parseShareholderMeetings(String stockCode, Map<String, Object> response) {
        if (response == null) {
            return List.of();
        }
        Object outBlockObj = response.get(SCHEDULE_OUT_BLOCK_KEY);
        if (!(outBlockObj instanceof List)) {
            log.warn("LS 증시일정 응답에서 {}을 찾지 못함 - stockCode: {}, 응답: {}", SCHEDULE_OUT_BLOCK_KEY, stockCode, response);
            return List.of();
        }
        List<Map<String, Object>> outBlock = (List<Map<String, Object>>) outBlockObj;

        List<LsShareholderMeetingDto> items = outBlock.stream()
                .filter(row -> SHAREHOLDER_MEETING_UPGU.equals(stringOf(row.get("upgu"))))
                // 주주총회 이력이라도 기준일이 없는(전체 조회 시 맨 앞에 오는 "00000000" 같은
                // 빈 항목) 행은 실제 일정이 아니므로 제외한다 — t3202 응답 예시(001200)에서도
                // 첫 행이 recdt="00000000"으로 실데이터 없이 온 사례가 실제로 확인됨.
                .filter(row -> {
                    String date = stringOf(row.get("recdt"));
                    return date != null && !date.isBlank() && !date.chars().allMatch(c -> c == '0');
                })
                .map(row -> LsShareholderMeetingDto.builder()
                        .date(stringOf(row.get("recdt")))
                        .eventName(stringOf(row.get("upunm")))
                        .build())
                .toList();
        return items.size() > MAX_SCHEDULE_ITEMS ? items.subList(0, MAX_SCHEDULE_ITEMS) : items;
    }

    private static final String FINANCIAL_RANKING_TR_CD = "t3341";
    private static final String FINANCIAL_RANKING_OUT_BLOCK_KEY = "t3341OutBlock1";
    private static final int MAX_FINANCIAL_RANKING_ITEMS = 10;

    private static final String OVERSEAS_INDEX_TR_CD = "t3521";
    private static final String OVERSEAS_INDEX_OUT_BLOCK_KEY = "t3521OutBlock";

    private static final String MARKET_LIQUIDITY_TR_CD = "t8428";
    private static final String MARKET_LIQUIDITY_OUT_BLOCK_KEY = "t8428OutBlock1";
    private static final int MAX_LIQUIDITY_ITEMS = 5;
    // 2026-08-13 추가 — 장기간(6개월/1년 등) 대기자금 추이를 물으면 5거래일로는 답이 안 되던
    // 문제 대응, 뉴스 검색과 동일한 2년 상한. 장기간 조회는 요약 계산을 위해 5건으로 자르지
    // 않는다(과도한 응답 방지용 안전 상한만 별도로 둠).
    private static final int MAX_PERIOD_MONTHS = 24;
    private static final int MAX_LONG_PERIOD_ITEMS = 500;

    /**
     * 재무지표(criteria) 기준 전체 종목 랭킹을 조회한다(t3341). "ROE 높은 종목 순위"처럼
     * 시장 전체를 훑는 질문에 답할 수 있는 몇 안 되는 LS TR이다(2026-08-11 확인).
     *
     * @param criteria 정렬 기준 LS 코드 — 1:매출액증가율 2:영업이익증가율 3:세전계속이익증가율
     *                 4:부채비율 5:유보율 6:EPS 7:BPS 8:ROE 9:PER a:PBR b:PEG. 실패하면 빈 리스트.
     */
    public List<LsFinancialRankingDto> getFinancialRanking(String criteria) {
        try {
            String token = accessTokenProvider.issueAccessToken();
            Map<String, Object> inBlock = new java.util.LinkedHashMap<>();
            inBlock.put("gubun", "0");
            inBlock.put("gubun1", criteria);
            inBlock.put("gubun2", "0");
            inBlock.put("cnt", MAX_FINANCIAL_RANKING_ITEMS);
            inBlock.put("exchgubun", "K");
            Map<String, Object> requestBody = Map.of("t3341InBlock", inBlock);

            Map<String, Object> response = call(investInfoUrl, FINANCIAL_RANKING_TR_CD, requestBody, token, "LS 재무순위종합 조회 실패");

            return parseFinancialRanking(response);
        } catch (CustomException e) {
            log.warn("LS 재무순위종합 조회 중 오류 - criteria: {}, 사유: {}", criteria, e.getMessage());
            return List.of();
        }
    }

    /**
     * 해외지수·환율·선물의 현재가를 조회한다(t3521). 예: kind="S", symbol="DJI@DJI"(다우),
     * "NAS@IXIC"(나스닥종합), "USDKRWSMBS"(원/달러 환율). 실패하거나 없으면 빈 값.
     */
    public Optional<LsOverseasIndexDto> getOverseasIndex(String kind, String symbol) {
        try {
            String token = accessTokenProvider.issueAccessToken();
            Map<String, Object> inBlock = new java.util.LinkedHashMap<>();
            inBlock.put("kind", kind);
            inBlock.put("symbol", symbol);
            Map<String, Object> requestBody = Map.of("t3521InBlock", inBlock);

            Map<String, Object> response = call(investInfoUrl, OVERSEAS_INDEX_TR_CD, requestBody, token, "LS 해외지수 조회 실패");

            return parseOverseasIndex(symbol, response);
        } catch (CustomException e) {
            log.warn("LS 해외지수 조회 중 오류 - symbol: {}, 사유: {}", symbol, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 증시 주변자금(고객예탁금·신용융자잔고 등) 최근 추이를 조회한다(t8428). 조회 범위를
     * 최근 5거래일로 짧게 제한해, AI가 원본 수치를 나열하지 않고 "최근 며칠간 대기자금이
     * 늘었다/줄었다" 식의 요약 문장으로 답하도록 유도한다(2026-08-11 합의 — 원본 수치를 많이
     * 주면 답변이 표처럼 딱딱해지는 문제를 데이터 조회량 자체를 줄여서 막는다).
     */
    public List<LsMarketLiquidityDto> getRecentMarketLiquidityTrend() {
        return getMarketLiquidityTrend(null);
    }

    /**
     * periodMonths가 없으면 기존과 동일하게 최근 5거래일치만 반환한다. periodMonths가
     * 있으면(2026-08-13 추가) 조회 시작일을 최대 {@value #MAX_PERIOD_MONTHS}개월(2년) 전까지
     * 넓히고, 요약 계산은 호출부가 하도록 5건으로 자르지 않는다.
     */
    public List<LsMarketLiquidityDto> getMarketLiquidityTrend(Integer periodMonths) {
        boolean longPeriod = periodMonths != null && periodMonths > 0;
        try {
            String token = accessTokenProvider.issueAccessToken();
            java.time.LocalDate today = java.time.LocalDate.now();
            String toDate = today.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
            java.time.LocalDate fromLocalDate = longPeriod
                    ? today.minusMonths(Math.min(periodMonths, MAX_PERIOD_MONTHS))
                    : today.minusDays(7);
            String fromDate = fromLocalDate.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
            int cap = longPeriod ? MAX_LONG_PERIOD_ITEMS : MAX_LIQUIDITY_ITEMS;
            Map<String, Object> inBlock = new java.util.LinkedHashMap<>();
            inBlock.put("fdate", fromDate);
            inBlock.put("tdate", toDate);
            inBlock.put("gubun", "0");
            inBlock.put("cnt", cap);
            inBlock.put("idx", 0);
            Map<String, Object> requestBody = Map.of("t8428InBlock", inBlock);

            Map<String, Object> response = call(investInfoUrl, MARKET_LIQUIDITY_TR_CD, requestBody, token, "LS 증시주변자금추이 조회 실패");

            return parseMarketLiquidity(response, cap);
        } catch (CustomException e) {
            log.warn("LS 증시주변자금추이 조회 중 오류 - 사유: {}", e.getMessage());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private List<LsFinancialRankingDto> parseFinancialRanking(Map<String, Object> response) {
        if (response == null) {
            return List.of();
        }
        Object outBlockObj = response.get(FINANCIAL_RANKING_OUT_BLOCK_KEY);
        if (!(outBlockObj instanceof List)) {
            log.warn("LS 재무순위종합 응답에서 {}을 찾지 못함 - 응답: {}", FINANCIAL_RANKING_OUT_BLOCK_KEY, response);
            return List.of();
        }
        List<Map<String, Object>> outBlock = (List<Map<String, Object>>) outBlockObj;
        List<LsFinancialRankingDto> items = new java.util.ArrayList<>();
        int rank = 1;
        for (Map<String, Object> row : outBlock) {
            items.add(LsFinancialRankingDto.builder()
                    .rank(rank++)
                    .stockCode(stringOf(row.get("shcode")))
                    .stockName(stringOf(row.get("hname")))
                    .roe(parseDoubleOrZero(row.get("roe")))
                    .per(parseDoubleOrZero(row.get("per")))
                    .pbr(parseDoubleOrZero(row.get("pbr")))
                    .salesGrowthRate(parseDoubleOrZero(row.get("salesgrowth")))
                    .build());
            if (items.size() >= MAX_FINANCIAL_RANKING_ITEMS) {
                break;
            }
        }
        return items;
    }

    @SuppressWarnings("unchecked")
    private Optional<LsOverseasIndexDto> parseOverseasIndex(String symbol, Map<String, Object> response) {
        if (response == null) {
            return Optional.empty();
        }
        Object outBlockObj = response.get(OVERSEAS_INDEX_OUT_BLOCK_KEY);
        if (!(outBlockObj instanceof Map)) {
            log.warn("LS 해외지수 응답에서 {}을 찾지 못함 - symbol: {}, 응답: {}", OVERSEAS_INDEX_OUT_BLOCK_KEY, symbol, response);
            return Optional.empty();
        }
        Map<String, Object> outBlock = (Map<String, Object>) outBlockObj;
        return Optional.of(LsOverseasIndexDto.builder()
                .symbol(symbol)
                .name(stringOf(outBlock.get("hname")))
                .price(parseDoubleOrZero(outBlock.get("close")))
                .changeAmount(parseDoubleOrZero(outBlock.get("change")))
                .changeRate(parseDoubleOrZero(outBlock.get("diff")))
                .date(stringOf(outBlock.get("date")))
                .build());
    }

    @SuppressWarnings("unchecked")
    private List<LsMarketLiquidityDto> parseMarketLiquidity(Map<String, Object> response, int cap) {
        if (response == null) {
            return List.of();
        }
        Object outBlockObj = response.get(MARKET_LIQUIDITY_OUT_BLOCK_KEY);
        if (!(outBlockObj instanceof List)) {
            log.warn("LS 증시주변자금추이 응답에서 {}을 찾지 못함 - 응답: {}", MARKET_LIQUIDITY_OUT_BLOCK_KEY, response);
            return List.of();
        }
        List<Map<String, Object>> outBlock = (List<Map<String, Object>>) outBlockObj;
        List<LsMarketLiquidityDto> items = new java.util.ArrayList<>();
        int count = 0;
        for (Map<String, Object> row : outBlock) {
            items.add(LsMarketLiquidityDto.builder()
                    .date(stringOf(row.get("date")))
                    .customerDepositAmount(parseLong(row.get("custmoney")))
                    .marginLoanAmount(parseLong(row.get("trjango")))
                    .build());
            if (++count >= cap) {
                break;
            }
        }
        return items;
    }

}
