package com.teamfp.aistock.domain.stock.service;

import com.teamfp.aistock.infra.marketdata.*;
import com.teamfp.aistock.infra.marketdata.dto.*;
import com.teamfp.aistock.infra.naver.NaverNewsApiClient;
import com.teamfp.aistock.infra.naver.dto.*;
import com.teamfp.aistock.domain.stock.dto.response.DividendScheduleResponse;
import com.teamfp.aistock.domain.stock.dto.response.StockDividendResearchResponse;
import com.teamfp.aistock.domain.stock.dto.response.StockEarningsResponse;
import com.teamfp.aistock.domain.stock.dto.response.StockFinancialYearResponse;
import com.teamfp.aistock.domain.stock.dto.response.StockOverviewResponse;
import com.teamfp.aistock.domain.stock.dto.response.StockResearchResponse;
import com.teamfp.aistock.domain.stock.dto.response.StockSearchSuggestion;
import com.teamfp.aistock.global.exception.*;
import com.teamfp.aistock.infra.dart.DartApiClient;
import com.teamfp.aistock.infra.dart.dto.DartCompanyResponse;
import com.teamfp.aistock.infra.dart.dto.DartDividendResponse;
import com.teamfp.aistock.infra.dart.dto.DartEarningsResponse;
import com.teamfp.aistock.infra.dart.dto.DartFinancialDetailResponse;
import com.teamfp.aistock.infra.dart.dto.DartFinancialRequest;
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
    private final DividendScheduleService dividendScheduleService;
    // DART 응답 캐시(2026-10-12 코드리뷰) — 리서치 탭을 열 때마다 DART를 부르지 않는다.
    private final com.teamfp.aistock.global.redis.RedisDartCacheService redisDartCacheService;


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

    // ===== 종목 리서치(fix/stock-detail-review) =====
    // 탭 6개: 개요(DART 기업개황·사업보고서 지표 + LS증권 시세 지표), 재무제표(DART 3개년), 분기 실적(DART 최근 분기 + 전년 동기),
    // 배당(DART 연간 지표 + 회차별 일정), 시가총액 비교(LS증권 시세), 투자의견(LS증권 수집, 생성기가 시뮬레이션 모드면 모의). 시세 지표는
    // local-market-data-generator가 LS증권 실시세(장중 실시간, 장외 마지막 거래일 종가)로 채운다(실시세 전환). 섹션마다 출처(sourceName·
    // sourceDetail·sourceUrl)와 값의 기준(notice)을 함께 내려 화면에서 원문과 바로 대조할 수 있게 한다. 등록 종목 105개 점검
    // (2026-10-11)으로 정한 유형별 규칙 — 금융업(매출액 없음)·우선주(발행 회사 공시)·무배당·ETF(DART 공시 없음)는 notice로 안내한다.
    private static final String SECTION_OVERVIEW = "overview";
    private static final String SECTION_FINANCE = "finance";
    private static final String SECTION_EARNINGS = "earnings";
    private static final String SECTION_DIVIDEND = "dividend";
    private static final String SECTION_PEERS = "peers";
    private static final String SECTION_ANALYSTS = "analysts";
    private static final java.util.Set<String> DART_SECTIONS = java.util.Set.of(SECTION_FINANCE, SECTION_EARNINGS, SECTION_DIVIDEND);
    private static final String REPORT_CODE_ANNUAL = "11011";
    // 배당 탭의 회차별 일정은 최근 3개 회계연도만 내려준다 — DB(dividend_schedules)에는 지급 기록과 연결된 과거 회차가 모두 남는다.
    private static final int DIVIDEND_SCHEDULE_YEARS = 3;
    private static final String CASH_DIVIDEND_PER_SHARE_LABEL = "주당 현금배당금(원)";
    // DART 캐시 기간 — 기업개황은 거의 바뀌지 않고, 보고서는 다음 날 장 시작 전(08:00)까지. 공시 시즌(사업보고서 3월, 분기·반기 5·8·11월)에는
    // 새 보고서가 하루에도 올라오므로 1시간.
    private static final java.time.Duration DART_COMPANY_TTL = java.time.Duration.ofDays(7);
    private static final java.time.Duration DART_EARNINGS_SEASON_TTL = java.time.Duration.ofHours(1);
    private static final java.util.Set<Integer> DART_FILING_SEASON_MONTHS = java.util.Set.of(3, 5, 8, 11);
    private static final String DART_UNAVAILABLE_NOTICE = "DART 응답이 일시적으로 원활하지 않아 공시 자료를 불러오지 못했습니다. 잠시 후 다시 확인해 주세요.";
    private static final String FINANCIAL_COMPANY_NOTICE =
            "금융업(은행·보험·증권)은 매출액 대신 이자수익·보험수익 등으로 공시해 매출액과 매출 기준 비율은 제공하지 않습니다.";

    public StockResearchResponse getResearch(String stockCode, String section) {
        validateCode(stockCode);
        if (SECTION_OVERVIEW.equals(section)) return getOverviewResearch(stockCode);
        if (SECTION_PEERS.equals(section)) return getPeersResearch(stockCode);
        if (SECTION_ANALYSTS.equals(section)) return getAnalystsResearch(stockCode);
        if (!DART_SECTIONS.contains(section)) {
            throw new CustomException(ErrorCode.INVALID_INPUT);
        }
        return getDartResearch(stockCode, section);
    }

    private StockResearchResponse getOverviewResearch(String stockCode) {
        // 시세가 없어도(생성기 중단·미등록 종목) DART 정보는 보여 준다 — 시세 지표만 비운다(2026-10-12 코드리뷰).
        CurrentPriceDetailDto price = marketDataApiClient.getCurrentPrice(stockCode).orElse(null);
        String marketNotice = price == null
                ? "시세 데이터를 불러오지 못해 시가총액·PER·PBR·52주 최고/최저·외국인 소진율은 비어 있습니다."
                : "시가총액·PER·PBR·52주 최고/최저·외국인 소진율은 LS증권 시세 기준입니다(장중 실시간, 장외에는 마지막 거래일 종가).";
        DartCompanyResponse company;
        List<DartFinancialDetailResponse> years;
        try {
            company = dartApiClient.resolveCorpCodeByStockCode(stockCode).flatMap(this::findCompany).orElse(null);
            years = company == null ? List.of() : findFinancialYears(company.corpCode());
        } catch (CustomException e) {
            if (!isDartFailure(e)) throw e;
            if (price == null) {
                return StockResearchResponse.unavailable(SECTION_OVERVIEW, DART_UNAVAILABLE_NOTICE + " 시세 데이터도 불러오지 못했습니다.");
            }
            // DART 장애 — 회사 정보·재무 지표 없이 LS증권 시세 지표만 보여준다(탭 전체를 오류로 만들지 않는다).
            return StockResearchResponse.of(SECTION_OVERVIEW, "LS증권 시세", "현재가 상세(t8407·t1102)", null,
                    DART_UNAVAILABLE_NOTICE + " " + marketNotice, StockOverviewResponse.of(null, null, price));
        }
        if (company == null && price == null) {
            return StockResearchResponse.unavailable(SECTION_OVERVIEW, "이 종목의 시세와 DART 기업개황을 모두 찾지 못했습니다.");
        }
        if (company == null) {
            // 회사 정보·재무 지표 없이 LS증권 시세 지표만 보여준다.
            return StockResearchResponse.of(SECTION_OVERVIEW, "LS증권 시세", "현재가 상세(t8407·t1102)", null,
                    (price.isEtf() ? "ETF는 DART 기업개황이 없어 회사 정보를 제공하지 않습니다. " : "DART에서 이 종목의 기업개황을 찾지 못했습니다. ")
                            + marketNotice,
                    StockOverviewResponse.of(null, null, price));
        }
        StockFinancialYearResponse financial = years.isEmpty() ? null : StockFinancialYearResponse.from(years.get(0));
        String financialNotice = financial == null
                ? "최근 사업보고서가 없어 재무 지표를 제공하지 않습니다."
                : "재무 지표(ROE·ROA·영업이익률·순이익률·EPS·부채비율·유동비율)는 DART %d 사업보고서(%s) 기준입니다."
                        .formatted(financial.bizYear(), fsDivName(years.get(0).fsDiv()))
                        + (financial.isFinancialCompany() ? " " + FINANCIAL_COMPANY_NOTICE : "");
        return StockResearchResponse.of(SECTION_OVERVIEW, "DART 기업개황",
                "기업개황(company.json) · 고유번호 %s%s".formatted(company.corpCode(),
                        financial == null ? "" : " · %d 사업보고서".formatted(financial.bizYear())),
                DartApiClient.companyPageUrl(company.corpCode()),
                preferredStockNotice(stockCode) + financialNotice + " " + marketNotice,
                StockOverviewResponse.of(company, financial, price));
    }

    private StockResearchResponse getPeersResearch(String stockCode) {
        MarketCapPeersDto peers = highItemApiClient.getMarketCapPeers(stockCode);
        if (peers.rank() == null) {
            // market이 있으면 시세 데이터에는 있는 종목(= ETF), 없으면 시세 데이터에 없는 종목이다.
            return StockResearchResponse.unavailable(SECTION_PEERS, peers.market() != null
                    ? "ETF는 상장주식수가 없어 시가총액 비교를 제공하지 않습니다."
                    : "시세 데이터에 이 종목이 없어 시가총액 순위를 계산하지 못했습니다.");
        }
        return StockResearchResponse.of(SECTION_PEERS, "LS증권 시세",
                "%s 등록 종목 %d개(ETF 제외) · 시가총액 = 현재가 × 상장주식수".formatted(peers.market(), peers.totalCount()), null,
                "서비스에 등록된 종목 안에서의 순위입니다(시장 전체 순위가 아님). 시가총액·PER·PBR은 LS증권 시세 기준이고, 우선주처럼 "
                        + "PER·PBR을 계산하지 않는 종목은 비어 있습니다.",
                peers);
    }

    private StockResearchResponse getAnalystsResearch(String stockCode) {
        List<InvestmentOpinionDto> opinions = investInfoApiClient.getInvestmentOpinions(stockCode);
        java.util.Optional<String> collectedAt = investInfoApiClient.getInvestmentOpinionCollectedAt(stockCode);
        if (opinions.isEmpty()) {
            return StockResearchResponse.unavailable(SECTION_ANALYSTS, collectedAt.isPresent()
                    ? "%s 기준 최근 1년 안에 나온 이 종목의 증권사 투자의견이 없습니다.".formatted(collectedAt.get())
                    : "이 종목에 제공되는 투자의견이 없습니다(ETF는 제공하지 않습니다).");
        }
        if (collectedAt.isPresent()) {
            return StockResearchResponse.of(SECTION_ANALYSTS, "LS증권 투자의견",
                    "LS증권 투자의견(t3401) · %s 수집 · 최근 1년 중 최신 %d건".formatted(collectedAt.get(), opinions.size()), null,
                    "%s에 LS증권에서 수집한 증권사 투자의견 중 최근 1년 안의 것입니다(공개 원문 페이지는 없습니다). 이후 새로 나온 의견은 "
                            .formatted(collectedAt.get())
                            + "반영되지 않았고, 직전 의견·목표주가가 없는 줄은 그 증권사의 첫 의견입니다. 목표주가가 발표일 주가와 지나치게 "
                            + "동떨어진 줄(원본 입력 오류로 보이는 값)은 제외했습니다.",
                    opinions);
        }
        return StockResearchResponse.simulated(SECTION_ANALYSTS, "local-market-data-generator가 만든 증권사 투자의견·목표주가",
                "실제 증권사 리포트가 아닌 모의 데이터입니다. 투자 판단에 사용하지 마세요.", opinions);
    }

    private StockResearchResponse getDartResearch(String stockCode, String section) {
        java.util.Optional<String> corpCode;
        try {
            corpCode = dartApiClient.resolveCorpCodeByStockCode(stockCode);
        } catch (CustomException e) {
            if (!isDartFailure(e)) throw e;
            return SECTION_DIVIDEND.equals(section) ? getDividendResearch(stockCode, null, true)
                    : StockResearchResponse.unavailable(section, DART_UNAVAILABLE_NOTICE);
        }
        if (corpCode.isEmpty()) {
            // ETF·ETN 등은 DART 공시 회사가 아니다 — 이전에는 STOCK_NOT_FOUND 오류("주식 종목을 찾을 수 없습니다")가 났다.
            // 배당은 회차별 일정이 따로 있을 수 있어 그대로 조회한다.
            if (SECTION_DIVIDEND.equals(section)) {
                return getDividendResearch(stockCode, null, false);
            }
            boolean isEtf = marketDataApiClient.getCurrentPrice(stockCode).map(CurrentPriceDetailDto::isEtf).orElse(false);
            return StockResearchResponse.unavailable(section, isEtf
                    ? "ETF는 DART 재무 공시 대상이 아니어서 제공되는 자료가 없습니다."
                    : "DART에서 이 종목의 공시 회사를 찾지 못했습니다.");
        }
        try {
            return switch (section) {
                case SECTION_FINANCE -> getFinanceResearch(stockCode, corpCode.get());
                case SECTION_EARNINGS -> getEarningsResearch(stockCode, corpCode.get());
                default -> getDividendResearch(stockCode, corpCode.get(), false);
            };
        } catch (CustomException e) {
            if (!isDartFailure(e)) throw e;
            // DART 장애 — 배당은 DB에 있는 회차별 일정이라도 보여 주고, 재무·실적은 안내만 한다.
            return SECTION_DIVIDEND.equals(section) ? getDividendResearch(stockCode, null, true)
                    : StockResearchResponse.unavailable(section, DART_UNAVAILABLE_NOTICE);
        }
    }

    private StockResearchResponse getFinanceResearch(String stockCode, String corpCode) {
        List<DartFinancialDetailResponse> years = findFinancialYears(corpCode);
        if (years.isEmpty()) {
            int lastYear = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).getYear() - 1;
            return StockResearchResponse.unavailable(SECTION_FINANCE,
                    "DART에서 %d·%d 사업연도 사업보고서를 찾지 못했습니다.".formatted(lastYear, lastYear - 1));
        }
        DartFinancialDetailResponse latest = years.get(0);
        int oldestYear = years.get(years.size() - 1).bizYear();
        return StockResearchResponse.of(SECTION_FINANCE, "DART 사업보고서",
                "%d 사업연도 사업보고서 · %s · 단일회사 전체 재무제표(fnlttSinglAcntAll)".formatted(latest.bizYear(), fsDivName(latest.fsDiv())),
                DartApiClient.viewerUrl(latest.receiptNo()),
                preferredStockNotice(stockCode)
                        + "%d~%d년 값입니다(이전 해는 %d 사업보고서에 함께 실린 비교 값). 손익·현금흐름은 1~12월 합계, "
                        .formatted(oldestYear, latest.bizYear(), latest.bizYear())
                        + "재무상태는 12월 31일 기준이며 금액 단위는 원(EPS는 원/주)입니다. 공시에 없는 항목은 비어 있습니다."
                        + (latest.isFinancialCompany() ? " " + FINANCIAL_COMPANY_NOTICE : ""),
                years.stream().map(StockFinancialYearResponse::from).toList());
    }

    private StockResearchResponse getEarningsResearch(String stockCode, String corpCode) {
        DartEarningsResponse earnings = cachedDart(corpCode + ":earnings", reportTtl(),
                new tools.jackson.core.type.TypeReference<DartEarningsResponse>() { }, () -> dartApiClient.getRecentEarnings(corpCode));
        if (earnings.reportCode() == null) {
            return StockResearchResponse.unavailable(SECTION_EARNINGS, "DART에서 최근 분기·사업보고서를 찾지 못했습니다.");
        }
        return StockResearchResponse.of(SECTION_EARNINGS, "DART " + reportName(earnings.reportCode()),
                "%d 사업연도 %s · %s · 단일회사 전체 재무제표(fnlttSinglAcntAll)".formatted(earnings.bizYear(),
                        reportName(earnings.reportCode()), fsDivName(earnings.fsDiv())),
                DartApiClient.viewerUrl(earnings.receiptNo()),
                preferredStockNotice(stockCode)
                        + periodDescription(earnings.reportCode(), earnings.bizYear()) + " 금액 단위는 원입니다."
                        + (earnings.isFinancialCompany() ? " " + FINANCIAL_COMPANY_NOTICE : ""),
                StockEarningsResponse.from(earnings));
    }

    // 연간 지표는 DART(사업보고서 배당에 관한 사항), 회차별 일정은 local-market-data-generator dividends.json(→ DB) — 둘 중 하나만
    // 있어도 보여준다. ETF처럼 DART 공시 회사가 아니면 corpCode가 null이다.
    private StockResearchResponse getDividendResearch(String stockCode, String corpCode, boolean isDartUnavailable) {
        DartDividendResponse annual = corpCode == null ? null : cachedDart(corpCode + ":dividend", reportTtl(),
                new tools.jackson.core.type.TypeReference<DartDividendResponse>() { }, () -> dartApiClient.getDividendDisclosure(corpCode));
        if (annual != null && annual.indicators().isEmpty()) {
            annual = null;
        }
        List<DividendScheduleResponse> schedules = recentSchedules(dividendScheduleService.getSchedules(stockCode, null));
        if (annual == null && schedules.isEmpty()) {
            return StockResearchResponse.unavailable(SECTION_DIVIDEND, isDartUnavailable ? DART_UNAVAILABLE_NOTICE : corpCode == null
                    ? "DART 공시 회사가 아니어서(ETF 등) 배당 정보를 제공하지 않습니다."
                    : "최근 사업보고서에 배당에 관한 사항이 없고 등록된 배당 일정도 없습니다.");
        }
        String scheduleNote = schedules.isEmpty()
                ? "등록된 회차별 배당 일정이 없습니다."
                : "회차별 일정은 최근 %d개 회계연도이며, 배당 기준일(LS증권)과 주당 배당금(DART 분기·사업보고서)을 모아 둔 자료입니다."
                        .formatted(DIVIDEND_SCHEDULE_YEARS);
        if (annual == null) {
            return StockResearchResponse.of(SECTION_DIVIDEND, "배당 일정 자료", "회차별 배당 일정(최근 %d개 회계연도)".formatted(DIVIDEND_SCHEDULE_YEARS),
                    null, (isDartUnavailable ? DART_UNAVAILABLE_NOTICE + " 회차별 일정만 보여줍니다. "
                            : "최근 사업보고서의 연간 배당 지표가 없어 회차별 일정만 보여줍니다. ") + scheduleNote,
                    new StockDividendResearchResponse(null, schedules));
        }
        int year = annual.bizYear();
        boolean hasCashDividend = annual.indicators().stream()
                .anyMatch(indicator -> CASH_DIVIDEND_PER_SHARE_LABEL.equals(indicator.label()));
        return StockResearchResponse.of(SECTION_DIVIDEND, "DART 사업보고서",
                "%d 사업연도 사업보고서 · 배당에 관한 사항(alotMatter) · 결산기준일 %s".formatted(year, annual.settlementDate()),
                DartApiClient.viewerUrl(annual.receiptNo()),
                preferredStockNotice(stockCode)
                        + (hasCashDividend ? "" : "%d~%d년에 현금배당이 없었습니다(아래는 배당성향 계산 근거인 순이익·주당순이익). "
                                .formatted(year - 2, year))
                        + "연간 지표의 당기는 %d년, 전기는 %d년, 전전기는 %d년 1~12월 값입니다. ".formatted(year, year - 1, year - 2)
                        + scheduleNote,
                new StockDividendResearchResponse(annual, schedules));
    }

    // 기업개황이 없는 회사도 "없음"(빈 목록)으로 캐시한다 — 공개 화면을 열 때마다 DART를 다시 부르지 않게(2026-10-12 코드리뷰).
    private java.util.Optional<DartCompanyResponse> findCompany(String corpCode) {
        List<DartCompanyResponse> company = cachedDart(corpCode + ":company", DART_COMPANY_TTL,
                new tools.jackson.core.type.TypeReference<List<DartCompanyResponse>>() { },
                () -> dartApiClient.getCompany(corpCode).map(List::of).orElse(List.of()));
        return company == null || company.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(company.get(0));
    }

    // 최근 사업보고서 3개년 — 작년 보고서가 아직 없으면(매년 1월~3월 말 공시 전) 재작년 보고서로 대신한다(2026-10-12 코드리뷰).
    private List<DartFinancialDetailResponse> findFinancialYears(String corpCode) {
        int lastYear = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).getYear() - 1;
        for (int year = lastYear; year >= lastYear - 1; year--) {
            int bizYear = year;
            List<DartFinancialDetailResponse> years = cachedDart(corpCode + ":finance:" + bizYear, reportTtl(),
                    new tools.jackson.core.type.TypeReference<List<DartFinancialDetailResponse>>() { },
                    () -> dartApiClient.getFinancialTrend(new DartFinancialRequest(corpCode, bizYear)));
            if (years != null && !years.isEmpty()) {
                return years;
            }
        }
        return List.of();
    }

    private <T> T cachedDart(String key, java.time.Duration ttl, tools.jackson.core.type.TypeReference<T> type,
                             java.util.function.Supplier<T> loader) {
        return redisDartCacheService.getOrLoad(key, ttl, type, loader);
    }

    // 보고서 캐시 기간 — 공시 시즌이면 1시간, 아니면 다음 날 장 시작 전(08:00)까지.
    private static java.time.Duration reportTtl() {
        return DART_FILING_SEASON_MONTHS.contains(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).getMonthValue())
                ? DART_EARNINGS_SEASON_TTL : com.teamfp.aistock.global.redis.RedisDartCacheService.ttlUntilNextMorning();
    }

    private static boolean isDartFailure(CustomException e) {
        return e.getErrorCode() == ErrorCode.EXTERNAL_API_ERROR || e.getErrorCode() == ErrorCode.DART_RATE_LIMIT_EXCEEDED;
    }

    // 가장 최근 회계연도부터 DIVIDEND_SCHEDULE_YEARS개 연도의 회차만 남긴다(최신 연도 기준이라 새 연도 회차가 들어오면 가장 오래된 해가
    // 자동으로 빠진다).
    private List<DividendScheduleResponse> recentSchedules(List<DividendScheduleResponse> schedules) {
        java.util.Set<String> recentYears = schedules.stream().map(DividendScheduleResponse::fiscalYear)
                .filter(java.util.Objects::nonNull)
                .sorted(java.util.Comparator.reverseOrder())
                .distinct()
                .limit(DIVIDEND_SCHEDULE_YEARS)
                .collect(java.util.stream.Collectors.toSet());
        return schedules.stream().filter(schedule -> recentYears.contains(schedule.fiscalYear())).toList();
    }

    // 우선주(종목코드 끝자리 5·7·8·9)는 DART에 따로 공시 회사가 없어 보통주 발행 회사의 공시를 쓴다
    // (DartApiClient.resolveCorpCodeByStockCode()의 우선주 대체 규칙과 같다).
    private static String preferredStockNotice(String stockCode) {
        char last = stockCode.charAt(stockCode.length() - 1);
        return last == '5' || last == '7' || last == '8' || last == '9'
                ? "우선주는 발행 회사(보통주) 공시 기준입니다. "
                : "";
    }

    // 분기·반기 보고서의 손익은 DART thstrm_amount — 그 분기 3개월치(누적값이 아님)이고, 전년 같은 분기는 frmtrm_q_amount다.
    // 12월 결산 법인 기준으로 기간을 표기한다.
    private String periodDescription(String reportCode, int year) {
        return switch (reportCode) {
            case "11013" -> "%d년 1분기(1~3월) 3개월 실적과 전년 같은 분기(%d년 1~3월) 비교입니다.".formatted(year, year - 1);
            case "11012" -> "%d년 2분기(4~6월) 3개월 실적과 전년 같은 분기(%d년 4~6월) 비교입니다.".formatted(year, year - 1);
            case "11014" -> "%d년 3분기(7~9월) 3개월 실적과 전년 같은 분기(%d년 7~9월) 비교입니다.".formatted(year, year - 1);
            default -> "분기 보고서를 찾지 못해 %d년 1~12월 연간 실적과 %d년 연간 실적을 비교합니다.".formatted(year, year - 1);
        };
    }

    private static String reportName(String reportCode) {
        return switch (reportCode) {
            case "11013" -> "1분기보고서";
            case "11012" -> "반기보고서";
            case "11014" -> "3분기보고서";
            case REPORT_CODE_ANNUAL -> "사업보고서";
            default -> "정기보고서";
        };
    }

    private static String fsDivName(String fsDiv) {
        return "OFS".equals(fsDiv) ? "개별재무제표" : "연결재무제표";
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
