package com.teamfp.aistock.domain.stock.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.teamfp.aistock.domain.stock.dto.response.DividendScheduleResponse;
import com.teamfp.aistock.domain.stock.dto.response.StockDividendResearchResponse;
import com.teamfp.aistock.domain.stock.dto.response.StockEarningsResponse;
import com.teamfp.aistock.domain.stock.dto.response.StockFinancialYearResponse;
import com.teamfp.aistock.domain.stock.dto.response.StockOverviewResponse;
import com.teamfp.aistock.domain.stock.dto.response.StockResearchResponse;
import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;
import com.teamfp.aistock.infra.dart.DartApiClient;
import com.teamfp.aistock.infra.dart.dto.DartCompanyResponse;
import com.teamfp.aistock.infra.dart.dto.DartDividendResponse;
import com.teamfp.aistock.infra.dart.dto.DartEarningsResponse;
import com.teamfp.aistock.infra.dart.dto.DartFinancialDetailResponse;
import com.teamfp.aistock.infra.dart.dto.DartFinancialRequest;
import com.teamfp.aistock.infra.dart.dto.ListedStock;
import com.teamfp.aistock.infra.marketdata.HighItemApiClient;
import com.teamfp.aistock.infra.marketdata.IndustryApiClient;
import com.teamfp.aistock.infra.marketdata.InvestInfoApiClient;
import com.teamfp.aistock.infra.marketdata.MarketDataApiClient;
import com.teamfp.aistock.infra.marketdata.dto.CurrentPriceDetailDto;
import com.teamfp.aistock.infra.marketdata.dto.InvestmentOpinionDto;
import com.teamfp.aistock.infra.marketdata.dto.MarketCapPeersDto;
import com.teamfp.aistock.infra.marketdata.dto.RankingItemDto;
import com.teamfp.aistock.infra.naver.NaverNewsApiClient;

@ExtendWith(MockitoExtension.class)
class MarketQueryServiceTest {

    @Mock private HighItemApiClient highItemApiClient;
    @Mock private MarketDataApiClient marketDataApiClient;
    @Mock private NaverNewsApiClient naverNewsApiClient;
    @Mock private IndustryApiClient industryApiClient;
    @Mock private InvestInfoApiClient investInfoApiClient;
    @Mock private DartApiClient dartApiClient;
    @Mock private DividendScheduleService dividendScheduleService;
    // DART 캐시는 저장 없이 바로 원본을 부르게 한다(캐시 동작 자체는 RedisDartCacheServiceTest에서 본다).
    @Mock private com.teamfp.aistock.global.redis.RedisDartCacheService redisDartCacheService;

    @InjectMocks
    private MarketQueryService marketQueryService;

    @org.junit.jupiter.api.BeforeEach
    void passThroughDartCache() {
        org.mockito.Mockito.lenient().when(redisDartCacheService.getOrLoad(
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> ((java.util.function.Supplier<?>) invocation.getArgument(3)).get());
    }

    private List<RankingItemDto> ranking(String stockCode) {
        return List.of(RankingItemDto.builder().rank(1).stockCode(stockCode).build());
    }

    @Test
    @DisplayName("종목 추천은 상장 종목명과 코드를 반환한다")
    void suggestStocks_returnsListedMatches() {
        when(dartApiClient.searchListedStocks("삼성", 8))
                .thenReturn(List.of(new ListedStock("005930", "삼성전자")));

        assertThat(marketQueryService.suggestStocks("삼성"))
                .singleElement()
                .satisfies(stock -> {
                    assertThat(stock.stockCode()).isEqualTo("005930");
                    assertThat(stock.stockName()).isEqualTo("삼성전자");
                });
    }

    // 삼성전자 2025 사업보고서(연결) 실제 값 기준의 상세 재무 — 필요한 계정만 채우고 나머지는 null.
    private static DartFinancialDetailResponse detail(int year, boolean isFinancialCompany, Long revenue, Long operatingProfit,
                                                      Long netIncome, Long totalAssets, Long totalLiabilities, Long totalEquity) {
        return new DartFinancialDetailResponse("00126380", year, "11011", "CFS", "20260310002820", isFinancialCompany,
                revenue, null, operatingProfit, null, netIncome, null, null, null, null, null,
                totalAssets, totalLiabilities, null, null, totalEquity, null, null, null, null);
    }

    @Test
    @DisplayName("재무제표는 시세 조회 없이 DART 사업보고서 3개년을 비율과 함께 내려준다")
    void getResearch_finance_returnsThreeYearsFromDart() {
        String corpCode = "00126380";
        int year = java.time.LocalDate.now().getYear() - 1;
        DartFinancialRequest request = new DartFinancialRequest(corpCode, year);
        List<DartFinancialDetailResponse> years = List.of(
                detail(year, false, 333_605_938_000_000L, 43_601_051_000_000L, 45_206_805_000_000L,
                        566_942_110_000_000L, 130_621_773_000_000L, 436_320_337_000_000L),
                detail(year - 1, false, 300_870_903_000_000L, null, null, null, null, null));
        when(dartApiClient.resolveCorpCodeByStockCode("005930")).thenReturn(Optional.of(corpCode));
        when(dartApiClient.getFinancialTrend(request)).thenReturn(years);

        StockResearchResponse response = marketQueryService.getResearch("005930", "finance");

        assertThat(response.isSimulated()).isFalse();
        assertThat(response.sourceName()).isEqualTo("DART 사업보고서");
        assertThat(response.sourceDetail()).contains(year + " 사업연도", "연결재무제표");
        assertThat(response.sourceUrl()).isEqualTo("https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20260310002820");
        assertThat(response.notice()).contains((year - 1) + "~" + year + "년").doesNotContain("우선주", "금융업");
        @SuppressWarnings("unchecked")
        List<StockFinancialYearResponse> content = (List<StockFinancialYearResponse>) response.content();
        assertThat(content).extracting(StockFinancialYearResponse::bizYear).containsExactly(year, year - 1);
        StockFinancialYearResponse latest = content.get(0);
        // 부채비율 130,621,773 ÷ 436,320,337 = 29.94%, 영업이익률 43,601,051 ÷ 333,605,938 = 13.07%,
        // 순이익률 45,206,805 ÷ 333,605,938 = 13.55%, ROE(지배주주 값 없음 → 전체) 45,206,805 ÷ 436,320,337 = 10.36%,
        // ROA 45,206,805 ÷ 566,942,110 = 7.97%
        assertThat(latest.debtRatio()).isEqualTo(29.94);
        assertThat(latest.operatingMargin()).isEqualTo(13.07);
        assertThat(latest.netMargin()).isEqualTo(13.55);
        assertThat(latest.roe()).isEqualTo(10.36);
        assertThat(latest.roa()).isEqualTo(7.97);
        assertThat(latest.currentRatio()).isNull();
        assertThat(content.get(1).debtRatio()).isNull();
        verifyNoInteractions(marketDataApiClient);
    }

    @Test
    @DisplayName("금융업은 매출 기준 비율 없이 금융업 안내를 붙이고, 우선주는 발행 회사 공시 안내를 붙인다")
    void getResearch_finance_financialCompanyAndPreferredStock() {
        int year = java.time.LocalDate.now().getYear() - 1;
        when(dartApiClient.resolveCorpCodeByStockCode("105565")).thenReturn(Optional.of("00688996"));
        when(dartApiClient.getFinancialTrend(new DartFinancialRequest("00688996", year))).thenReturn(List.of(
                detail(year, true, null, 8_517_711_000_000L, 5_840_715_000_000L, 1L, 1L, 1L)));

        StockResearchResponse response = marketQueryService.getResearch("105565", "finance");

        assertThat(response.notice()).contains("우선주는 발행 회사(보통주) 공시 기준입니다", "금융업(은행·보험·증권)");
        @SuppressWarnings("unchecked")
        StockFinancialYearResponse latest = ((List<StockFinancialYearResponse>) response.content()).get(0);
        assertThat(latest.isFinancialCompany()).isTrue();
        assertThat(latest.operatingMargin()).isNull();
    }

    @Test
    @DisplayName("분기 실적은 어느 분기 3개월 값인지와 전년 같은 분기 대비 증감률을 내려준다")
    void getResearch_earnings_returnsQuarterWithYoY() {
        DartEarningsResponse earnings = new DartEarningsResponse("00126380", 2026, "11012", "CFS", "20260814003699", false,
                171_499_470_000_000L, 89_492_412_000_000L, 71_624_461_000_000L,
                74_566_317_000_000L, 4_676_057_000_000L, -1L);
        when(dartApiClient.resolveCorpCodeByStockCode("005930")).thenReturn(Optional.of("00126380"));
        when(dartApiClient.getRecentEarnings("00126380")).thenReturn(earnings);

        StockResearchResponse response = marketQueryService.getResearch("005930", "earnings");

        assertThat(response.sourceName()).isEqualTo("DART 반기보고서");
        assertThat(response.notice()).contains("2026년 2분기(4~6월) 3개월 실적", "2025년 4~6월");
        StockEarningsResponse content = (StockEarningsResponse) response.content();
        // (171,499,470 − 74,566,317) ÷ 74,566,317 × 100 = 129.996 → 130.0%
        assertThat(content.revenueGrowthRate()).isEqualTo(130.0);
        // 전년이 적자(0 이하)면 증감률은 의미가 없어 null
        assertThat(content.netIncomeGrowthRate()).isNull();
    }

    private static DividendScheduleResponse schedule(String fiscalYear) {
        DividendScheduleResponse schedule = org.mockito.Mockito.mock(DividendScheduleResponse.class);
        org.mockito.Mockito.lenient().when(schedule.fiscalYear()).thenReturn(fiscalYear);
        return schedule;
    }

    @Test
    @DisplayName("배당은 DART 연간 지표와 최근 3개 회계연도 배당 일정을 한 응답으로 내려준다")
    void getResearch_dividend_combinesAnnualAndRecentSchedules() {
        DartDividendResponse dividend = new DartDividendResponse("00126380", 2025, "11011", "20260310002820", "2025-12-31",
                List.of(new DartDividendResponse.DividendIndicator("주당 현금배당금(원)", "보통주", 1668.0, 1446.0, 1444.0)));
        DividendScheduleResponse old = schedule("2022");
        List<DividendScheduleResponse> recent = List.of(schedule("2023"), schedule("2024"), schedule("2025"), schedule("2025"));
        when(dartApiClient.resolveCorpCodeByStockCode("005930")).thenReturn(Optional.of("00126380"));
        when(dartApiClient.getDividendDisclosure("00126380")).thenReturn(dividend);
        List<DividendScheduleResponse> all = new java.util.ArrayList<>(recent);
        all.add(0, old);
        when(dividendScheduleService.getSchedules("005930", null)).thenReturn(all);

        StockResearchResponse response = marketQueryService.getResearch("005930", "dividend");

        StockDividendResearchResponse content = (StockDividendResearchResponse) response.content();
        assertThat(content.annual()).isSameAs(dividend);
        // DB에는 2022년 회차도 있지만 응답은 최근 3개 회계연도(2023~2025)만
        assertThat(content.schedules()).containsExactlyElementsOf(recent);
        assertThat(response.notice()).contains("당기는 2025년", "전기는 2024년", "전전기는 2023년", "최근 3개 회계연도")
                .doesNotContain("현금배당이 없었습니다");
        verify(dartApiClient, never()).getDisclosureInfo("00126380", "배당사항");
    }

    @Test
    @DisplayName("주당 현금배당금 줄이 없으면(무배당) 현금배당이 없었다고 안내한다")
    void getResearch_dividend_noCashDividend() {
        DartDividendResponse dividend = new DartDividendResponse("01600000", 2025, "11011", "20260310000001", "2025-12-31",
                List.of(new DartDividendResponse.DividendIndicator("(연결)당기순이익(백만원)", null, -1072810.0, -1018741.0, 1237180.0)));
        when(dartApiClient.resolveCorpCodeByStockCode("373220")).thenReturn(Optional.of("01600000"));
        when(dartApiClient.getDividendDisclosure("01600000")).thenReturn(dividend);
        when(dividendScheduleService.getSchedules("373220", null)).thenReturn(List.of());

        StockResearchResponse response = marketQueryService.getResearch("373220", "dividend");

        assertThat(response.notice()).contains("2023~2025년에 현금배당이 없었습니다", "등록된 회차별 배당 일정이 없습니다");
    }

    @Test
    @DisplayName("DART 공시 회사가 없는 ETF는 재무 탭에서 오류 대신 미제공 안내를 내려준다")
    void getResearch_etf_returnsUnavailableNotice() {
        when(dartApiClient.resolveCorpCodeByStockCode("069500")).thenReturn(Optional.empty());
        CurrentPriceDetailDto etf = org.mockito.Mockito.mock(CurrentPriceDetailDto.class);
        when(etf.isEtf()).thenReturn(true);
        when(marketDataApiClient.getCurrentPrice("069500")).thenReturn(Optional.of(etf));

        StockResearchResponse response = marketQueryService.getResearch("069500", "finance");

        assertThat(response.content()).isNull();
        assertThat(response.notice()).contains("ETF");
    }

    @Test
    @DisplayName("작년 사업보고서가 아직 없으면(1~3월) 재작년 보고서로 재무 탭을 채운다(2026-10-12 코드리뷰)")
    void getResearch_finance_fallsBackToYearBefore() {
        int lastYear = java.time.LocalDate.now().getYear() - 1;
        when(dartApiClient.resolveCorpCodeByStockCode("005930")).thenReturn(Optional.of("00126380"));
        when(dartApiClient.getFinancialTrend(new DartFinancialRequest("00126380", lastYear))).thenReturn(List.of());
        when(dartApiClient.getFinancialTrend(new DartFinancialRequest("00126380", lastYear - 1))).thenReturn(List.of(
                detail(lastYear - 1, false, 300_870_903_000_000L, null, null, null, null, null)));

        StockResearchResponse response = marketQueryService.getResearch("005930", "finance");

        assertThat(response.sourceDetail()).contains((lastYear - 1) + " 사업연도");
        @SuppressWarnings("unchecked")
        List<StockFinancialYearResponse> content = (List<StockFinancialYearResponse>) response.content();
        assertThat(content).extracting(StockFinancialYearResponse::bizYear).containsExactly(lastYear - 1);
    }

    @Test
    @DisplayName("DART 장애면 개요는 LS 시세 지표만, 배당은 DB 일정만, 재무는 안내만 — 탭 전체를 오류로 만들지 않는다(2026-10-12 코드리뷰)")
    void getResearch_dartFailure_fallsBackPerSection() {
        CurrentPriceDetailDto price = org.mockito.Mockito.mock(CurrentPriceDetailDto.class);
        when(marketDataApiClient.getCurrentPrice("005930")).thenReturn(Optional.of(price));
        when(dartApiClient.resolveCorpCodeByStockCode("005930")).thenReturn(Optional.of("00126380"));
        when(dartApiClient.getCompany("00126380")).thenThrow(new CustomException(ErrorCode.EXTERNAL_API_ERROR));
        when(dartApiClient.getFinancialTrend(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new CustomException(ErrorCode.DART_RATE_LIMIT_EXCEEDED));
        when(dartApiClient.getDividendDisclosure("00126380")).thenThrow(new CustomException(ErrorCode.EXTERNAL_API_ERROR));
        DividendScheduleResponse schedule = schedule("2025");
        when(dividendScheduleService.getSchedules("005930", null)).thenReturn(List.of(schedule));

        StockResearchResponse overview = marketQueryService.getResearch("005930", "overview");
        assertThat(overview.sourceName()).isEqualTo("LS증권 시세");
        assertThat(overview.notice()).contains("DART 응답이 일시적으로 원활하지 않아");
        assertThat(((StockOverviewResponse) overview.content()).company()).isNull();

        StockResearchResponse dividend = marketQueryService.getResearch("005930", "dividend");
        assertThat(((StockDividendResearchResponse) dividend.content()).schedules()).containsExactly(schedule);
        assertThat(dividend.notice()).contains("DART 응답이 일시적으로 원활하지 않아", "회차별 일정만");

        StockResearchResponse finance = marketQueryService.getResearch("005930", "finance");
        assertThat(finance.content()).isNull();
        assertThat(finance.notice()).contains("DART 응답이 일시적으로 원활하지 않아");
    }

    @Test
    @DisplayName("DART 고유번호 목록을 못 받으면(잘못된 키·장애) '공시 회사 없음'·'ETF 등'이 아니라 DART 일시 장애로 안내한다(2026-10-13)")
    void getResearch_corpCodeListUnavailable_saysDartUnavailable() {
        CurrentPriceDetailDto price = org.mockito.Mockito.mock(CurrentPriceDetailDto.class);
        when(marketDataApiClient.getCurrentPrice("005930")).thenReturn(Optional.of(price));
        when(dartApiClient.resolveCorpCodeByStockCode("005930")).thenThrow(new CustomException(ErrorCode.EXTERNAL_API_ERROR));
        when(dividendScheduleService.getSchedules("005930", null)).thenReturn(List.of());

        for (String section : List.of("overview", "finance", "earnings", "dividend")) {
            StockResearchResponse response = marketQueryService.getResearch("005930", section);
            assertThat(response.notice()).as(section).contains("DART 응답이 일시적으로 원활하지 않아")
                    .doesNotContain("찾지 못했습니다", "ETF");
        }
    }

    @Test
    @DisplayName("시세가 없어도 개요는 DART 기업 정보·재무 지표를 보여 주고 시세 지표만 비운다(2026-10-12 코드리뷰)")
    void getResearch_overview_withoutPrice_showsDartOnly() {
        DartCompanyResponse company = new DartCompanyResponse("00126380", "삼성전자(주)", null, "삼성전자", null, "Y", null, "12",
                null, null, null, null, null, null, null);
        int year = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).getYear() - 1;
        when(marketDataApiClient.getCurrentPrice("005930")).thenReturn(Optional.empty());
        when(dartApiClient.resolveCorpCodeByStockCode("005930")).thenReturn(Optional.of("00126380"));
        when(dartApiClient.getCompany("00126380")).thenReturn(Optional.of(company));
        when(dartApiClient.getFinancialTrend(new DartFinancialRequest("00126380", year))).thenReturn(List.of(
                detail(year, false, 333_605_938_000_000L, 43_601_051_000_000L, 45_206_805_000_000L,
                        566_942_110_000_000L, 130_621_773_000_000L, 436_320_337_000_000L)));

        StockResearchResponse response = marketQueryService.getResearch("005930", "overview");

        StockOverviewResponse content = (StockOverviewResponse) response.content();
        assertThat(response.sourceName()).isEqualTo("DART 기업개황");
        assertThat(response.notice()).contains("시세 데이터를 불러오지 못해");
        assertThat(content.company()).isSameAs(company);
        assertThat(content.financial()).isNotNull();
        assertThat(content.marketCap()).isNull();
        assertThat(content.per()).isNull();
    }

    @Test
    @DisplayName("ETF 배당 탭은 DART 지표 없이 회차별 일정만 있으면 일정만 내려준다")
    void getResearch_etfDividend_returnsSchedulesOnly() {
        DividendScheduleResponse schedule = schedule("2025");
        when(dartApiClient.resolveCorpCodeByStockCode("069500")).thenReturn(Optional.empty());
        when(dividendScheduleService.getSchedules("069500", null)).thenReturn(List.of(schedule));

        StockResearchResponse response = marketQueryService.getResearch("069500", "dividend");

        StockDividendResearchResponse content = (StockDividendResearchResponse) response.content();
        assertThat(content.annual()).isNull();
        assertThat(content.schedules()).containsExactly(schedule);
    }

    @Test
    @DisplayName("개요는 DART 기업개황·사업보고서 지표와 시세 기반 지표를 함께 내려주고 DART 기업개황 링크를 건다")
    void getResearch_overview_combinesCompanyFinancialAndMarket() {
        CurrentPriceDetailDto price = org.mockito.Mockito.mock(CurrentPriceDetailDto.class);
        when(price.getCurrentPrice()).thenReturn(260_500L);
        when(price.getListingShares()).thenReturn(5_846_279L);
        when(price.getMarket()).thenReturn("KOSPI");
        DartCompanyResponse company = new DartCompanyResponse("00126380", "삼성전자(주)", "SAMSUNG ELECTRONICS CO,.LTD", "삼성전자",
                "전영현, 노태문", "Y", "19690113", "12", "https://www.samsung.com/sec", null, "02-2255-0114",
                "경기도 수원시 영통구 삼성로 129", "1301110006246", "1248100998", "264");
        int year = java.time.LocalDate.now().getYear() - 1;
        when(marketDataApiClient.getCurrentPrice("005930")).thenReturn(Optional.of(price));
        when(dartApiClient.resolveCorpCodeByStockCode("005930")).thenReturn(Optional.of("00126380"));
        when(dartApiClient.getCompany("00126380")).thenReturn(Optional.of(company));
        when(dartApiClient.getFinancialTrend(new DartFinancialRequest("00126380", year))).thenReturn(List.of(
                detail(year, false, 333_605_938_000_000L, 43_601_051_000_000L, 45_206_805_000_000L,
                        566_942_110_000_000L, 130_621_773_000_000L, 436_320_337_000_000L)));

        StockResearchResponse response = marketQueryService.getResearch("005930", "overview");

        StockOverviewResponse content = (StockOverviewResponse) response.content();
        assertThat(response.sourceName()).isEqualTo("DART 기업개황");
        assertThat(response.sourceUrl()).isEqualTo("https://dart.fss.or.kr/dsae001/selectPopup.ax?selectKey=00126380");
        assertThat(response.notice()).contains("DART " + year + " 사업보고서", "LS증권 시세 기준");
        assertThat(content.company()).isSameAs(company);
        assertThat(content.financial().roe()).isEqualTo(10.36);
        assertThat(content.market()).isEqualTo("KOSPI");
        assertThat(content.marketCap()).isEqualTo(260_500L * 5_846_279L * 1000);
    }

    @Test
    @DisplayName("시가총액 비교는 보고 있는 종목의 같은 시장 순위를 LS증권 시세 출처로 내려준다")
    void getResearch_peers_usesStockMarketRanking() {
        MarketCapPeersDto peers = new MarketCapPeersDto("005930", "삼성전자", "KOSPI", 1, 70, ranking("005930"));
        when(highItemApiClient.getMarketCapPeers("005930")).thenReturn(peers);

        StockResearchResponse response = marketQueryService.getResearch("005930", "peers");

        assertThat(response.content()).isSameAs(peers);
        assertThat(response.isSimulated()).isFalse();
        assertThat(response.sourceName()).isEqualTo("LS증권 시세");
        assertThat(response.sourceDetail()).contains("KOSPI 등록 종목 70개");
        verify(highItemApiClient, never()).getTopMarketCap();
    }

    @Test
    @DisplayName("투자의견은 LS증권 스냅샷이 있으면 실제 데이터와 수집일을 출처로 표시한다")
    void getResearch_analysts_realSnapshot() {
        List<InvestmentOpinionDto> opinions = List.of(org.mockito.Mockito.mock(InvestmentOpinionDto.class));
        when(investInfoApiClient.getInvestmentOpinions("005930")).thenReturn(opinions);
        when(investInfoApiClient.getInvestmentOpinionCollectedAt("005930")).thenReturn(Optional.of("2026-10-11"));

        StockResearchResponse response = marketQueryService.getResearch("005930", "analysts");

        assertThat(response.isSimulated()).isFalse();
        assertThat(response.sourceName()).isEqualTo("LS증권 투자의견");
        assertThat(response.sourceDetail()).contains("t3401", "2026-10-11");
    }

    @Test
    @DisplayName("투자의견 스냅샷이 없으면 모의 데이터임을 표시한다")
    void getResearch_analysts_isMarkedSimulated() {
        List<InvestmentOpinionDto> opinions = List.of(org.mockito.Mockito.mock(InvestmentOpinionDto.class));
        when(investInfoApiClient.getInvestmentOpinions("005930")).thenReturn(opinions);
        when(investInfoApiClient.getInvestmentOpinionCollectedAt("005930")).thenReturn(Optional.empty());

        StockResearchResponse response = marketQueryService.getResearch("005930", "analysts");

        assertThat(response.isSimulated()).isTrue();
        assertThat(response.notice()).contains("모의 데이터");
    }

    @Test
    @DisplayName("지원하지 않는 섹션이면 DART를 부르지 않고 INVALID_INPUT을 던진다")
    void getResearch_unknownSection_throwsInvalidInput() {
        assertThatThrownBy(() -> marketQueryService.getResearch("005930", "unknown"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
        verifyNoInteractions(dartApiClient);
    }

    @Test
    @DisplayName("sort=rise는 전체 시장 상승률 상위 순위를 반환한다(#13)")
    void getRankings_rise_returnsWholeMarketRising() {
        when(highItemApiClient.getTopPriceChangeRate()).thenReturn(ranking("000001"));

        List<RankingItemDto> result = marketQueryService.getRankings("rise", false);

        assertThat(result).extracting(RankingItemDto::getStockCode).containsExactly("000001");
        verify(highItemApiClient, never()).getTopMarketCap();
    }

    @Test
    @DisplayName("sort=fall은 전체 시장 하락률 상위 순위를 반환한다(#13)")
    void getRankings_fall_returnsWholeMarketFalling() {
        when(highItemApiClient.getTopPriceDeclineRate()).thenReturn(ranking("000002"));

        List<RankingItemDto> result = marketQueryService.getRankings("fall", false);

        assertThat(result).extracting(RankingItemDto::getStockCode).containsExactly("000002");
        verify(highItemApiClient, never()).getTopPriceChangeRate();
    }

    @Test
    @DisplayName("sort=change는 기존 호출 호환을 위해 상승률 상위 순위를 반환한다")
    void getRankings_change_isRiseAlias() {
        when(highItemApiClient.getTopPriceChangeRate()).thenReturn(ranking("000001"));

        List<RankingItemDto> result = marketQueryService.getRankings("change", false);

        assertThat(result).extracting(RankingItemDto::getStockCode).containsExactly("000001");
    }

    @Test
    @DisplayName("지원하지 않는 sort 값이면 INVALID_INPUT을 던진다")
    void getRankings_unknownSort_throwsInvalidInput() {
        assertThatThrownBy(() -> marketQueryService.getRankings("unknown", false))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("all=true면 홈 무한 스크롤용으로 건수 제한 없는 순위 메서드를 호출한다")
    void getRankings_all_requestsUnlimitedRanking() {
        when(highItemApiClient.getTopMarketCap(Integer.MAX_VALUE)).thenReturn(ranking("000003"));

        List<RankingItemDto> result = marketQueryService.getRankings("market-cap", true);

        assertThat(result).extracting(RankingItemDto::getStockCode).containsExactly("000003");
        verify(highItemApiClient, never()).getTopMarketCap();
    }

    @Test
    @DisplayName("all=false면 기존처럼 상위 10건 순위 메서드를 호출한다")
    void getRankings_notAll_keepsDefaultRanking() {
        when(highItemApiClient.getTopVolume()).thenReturn(ranking("000004"));

        List<RankingItemDto> result = marketQueryService.getRankings("volume", false);

        assertThat(result).extracting(RankingItemDto::getStockCode).containsExactly("000004");
        verify(highItemApiClient, never()).getTopVolume(Integer.MAX_VALUE);
    }

    @Test
    @DisplayName("all=true여도 지원하지 않는 sort 값이면 INVALID_INPUT을 던진다")
    void getRankings_allWithUnknownSort_throwsInvalidInput() {
        assertThatThrownBy(() -> marketQueryService.getRankings("unknown", true))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("차트 조회는 dwmcode·count를 그대로 차트 전용 메서드에 넘기고 AI 상담용 months 경로는 쓰지 않는다")
    void getChartHistory_passesDwmcodeAndCount() {
        List<com.teamfp.aistock.infra.marketdata.dto.HistoricalPriceDto> weekly =
                List.of(com.teamfp.aistock.infra.marketdata.dto.HistoricalPriceDto.builder().date("20261001").build());
        when(marketDataApiClient.getChartPrices("005930", 2, 52)).thenReturn(weekly);

        assertThat(marketQueryService.getChartHistory("005930", 2, 52)).isSameAs(weekly);
        verify(marketDataApiClient, never()).getHistoricalPrices(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("차트 조회의 dwmcode가 1~3 밖이거나 count가 1~60 밖이면 INVALID_INPUT을 던진다")
    void getChartHistory_invalidInput_throws() {
        for (int[] invalid : new int[][] {{0, 60}, {4, 60}, {1, 0}, {1, 61}}) {
            assertThatThrownBy(() -> marketQueryService.getChartHistory("005930", invalid[0], invalid[1]))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_INPUT);
        }
        verifyNoInteractions(marketDataApiClient);
    }
}
