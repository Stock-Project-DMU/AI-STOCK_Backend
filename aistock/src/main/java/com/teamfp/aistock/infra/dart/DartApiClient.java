package com.teamfp.aistock.infra.dart;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;
import com.teamfp.aistock.global.util.ExternalApiInvoker;
import com.teamfp.aistock.infra.dart.dto.DartCompanyResponse;
import com.teamfp.aistock.infra.dart.dto.DartDividendResponse;
import com.teamfp.aistock.infra.dart.dto.DartEarningsResponse;
import com.teamfp.aistock.infra.dart.dto.DartFinancialDetailResponse;
import com.teamfp.aistock.infra.dart.dto.DartFinancialRequest;
import com.teamfp.aistock.infra.dart.dto.DartFinancialResponse;
import com.teamfp.aistock.infra.dart.dto.ListedStock;

import lombok.extern.slf4j.Slf4j;

/**
 * Open DART 단일회사 전체 재무제표(fnlttSinglAcntAll) 조회 담당.
 * 사업보고서(reprt_code=11011) 기준으로 연결재무제표(CFS)를 먼저 시도하고, 계정 목록이
 * 비어 있으면(자회사가 없어 연결재무제표를 작성하지 않는 회사 등) 개별재무제표(OFS)로
 * 한 번 더 조회한다.
 *
 * <p>추가로 회사명 → DART 고유번호(corp_code) 매핑도 담당한다. DART 재무제표 API는
 * 종목코드(예: 005930)가 아니라 DART가 자체적으로 매긴 8자리 corp_code로만 조회가 가능한데,
 * 이 매핑은 DART가 별도로 제공하는 "고유번호(corpCode.xml)" API로만 구할 수 있다. 이 API는
 * DART에 등록된 전체 회사 목록(corp_code+corp_name+stock_code)을 zip으로 묶어 내려주므로,
 * 매 요청마다 다시 받으면 낭비가 크다 — 최초 1회만 내려받아 애플리케이션 메모리에
 * (회사명→corp_code) 맵으로 캐싱해두고 이후 요청은 캐시에서 바로 찾는다. 이 목록은 자주
 * 바뀌지 않고(신규 상장/상장폐지 정도), 새 회사가 추가되면 애플리케이션을 재시작하면 되므로
 * 별도 TTL이나 주기적 갱신 없이 프로세스 생존 기간 동안만 유효한 캐시로 충분하다.</p>
 */
@Slf4j
@Component
public class DartApiClient {

    // DART 보고서 종류 코드. 분기 실적을 물어보면 사업보고서(연간)만으로는 최신 정보가 아니라서,
    // "가장 최근에 실제로 공시된" 분기부터 역순으로 시도한다(3분기→반기→1분기). 4분기 단독
    // 보고서는 없고 4분기 실적은 연간 사업보고서에 포함되므로, 분기 쪽에서 아무것도 못 찾으면
    // 마지막에 연간으로 대체한다.
    private static final String REPORT_CODE_BUSINESS = "11011";
    private static final String REPORT_CODE_Q3 = "11014";
    private static final String REPORT_CODE_HALF = "11012";
    private static final String REPORT_CODE_Q1 = "11013";
    // 날짜 계산으로 고른 분기 후보에도 데이터가 없을 때, 안정적으로 존재하는 "작년" 사업보고서
    // (연간)로 최종 대체한다 — AiPlanningService.DART_YEAR_OFFSET과 같은 의도.
    private static final int ANNUAL_FALLBACK_YEAR_OFFSET = 1;

    private static final String FS_DIV_CONSOLIDATED = "CFS";
    private static final String FS_DIV_INDIVIDUAL = "OFS";
    private static final String SUCCESS_STATUS = "000";
    private static final String NO_DATA_STATUS = "013";
    private static final String RATE_LIMIT_STATUS = "020";

    private static final String ACCOUNT_REVENUE = "매출액";
    private static final String ACCOUNT_OPERATING_PROFIT = "영업이익";
    private static final String ACCOUNT_TOTAL_ASSETS = "자산총계";
    private static final String ACCOUNT_TOTAL_LIABILITIES = "부채총계";
    private static final String ACCOUNT_TOTAL_EQUITY = "자본총계";

    // 당기순이익은 다른 5개 계정과 달리 보고서 종류에 따라 계정명 자체가 바뀐다 — 실제 DART
    // 응답으로 확인함(2026-08-04): 연간(사업보고서)은 "당기순이익", 반기보고서는 "반기순이익",
    // 1·3분기보고서는 "분기순이익"으로 온다. getRecentQuarterlyFinancials()가 여러 보고서
    // 종류를 오가며 조회하므로, 후보 이름을 전부 순서대로 시도한다.
    private static final List<String> NET_INCOME_ACCOUNT_NAMES = List.of("당기순이익", "반기순이익", "분기순이익");

    // 계정명은 회사마다 제각각이라(에코프로비엠은 매출액을 "매출", 반기순이익을 "반기손이익(손실)"으로 공시해 이름으로는
    // 못 찾았다 — 2026-10-11 실응답 확인) DART 표준 계정 ID로 먼저 찾고, ID가 없는 응답만 위 계정명으로 찾는다.
    private static final String ACCOUNT_ID_REVENUE = "ifrs-full_Revenue";
    private static final String ACCOUNT_ID_OPERATING_PROFIT = "dart_OperatingIncomeLoss";
    private static final String ACCOUNT_ID_NET_INCOME = "ifrs-full_ProfitLoss";
    private static final String ACCOUNT_ID_TOTAL_ASSETS = "ifrs-full_Assets";
    private static final String ACCOUNT_ID_TOTAL_LIABILITIES = "ifrs-full_Liabilities";
    private static final String ACCOUNT_ID_TOTAL_EQUITY = "ifrs-full_Equity";

    // 같은 계정 ID가 자본변동표(SCE)·현금흐름표(CF)에도 구성요소별로 여러 번 나오므로(삼성전자 반기 응답에서
    // ifrs-full_ProfitLoss가 SCE에만 7줄) 계정이 속한 재무제표를 정해 찾는다. 손익은 손익계산서(IS)를 먼저 보고,
    // 손익계산서를 따로 내지 않고 포괄손익계산서(CIS) 하나로 공시하는 회사(에코프로비엠)는 CIS에서 찾는다.
    private static final String STATEMENT_BALANCE_SHEET = "BS";
    private static final String STATEMENT_CASH_FLOW = "CF";
    private static final List<String> INCOME_STATEMENTS = List.of("IS", "CIS");

    // 종목 리서치 재무제표 상세용 계정 ID(fix/stock-detail-review) — 계정명 대체 없이 ID로만 찾는다.
    private static final String ACCOUNT_ID_GROSS_PROFIT = "ifrs-full_GrossProfit";
    private static final String ACCOUNT_ID_PROFIT_BEFORE_TAX = "ifrs-full_ProfitLossBeforeTax";
    private static final String ACCOUNT_ID_NET_INCOME_OWNERS = "ifrs-full_ProfitLossAttributableToOwnersOfParent";
    private static final String ACCOUNT_ID_NET_INCOME_NON_CONTROLLING = "ifrs-full_ProfitLossAttributableToNoncontrollingInterests";
    private static final String ACCOUNT_ID_EPS = "ifrs-full_BasicEarningsLossPerShare";
    private static final String ACCOUNT_ID_EPS_CONTINUING = "ifrs-full_BasicEarningsLossPerShareFromContinuingOperations";
    private static final String ACCOUNT_ID_EPS_DISCONTINUED = "ifrs-full_BasicEarningsLossPerShareFromDiscontinuedOperations";
    private static final List<String> EPS_ACCOUNT_NAMES = List.of(
            "기본주당이익", "기본주당순이익", "보통주 기본주당이익", "보통주 기본주당순이익", "보통주 기본/희석주당순이익");
    private static final String ACCOUNT_ID_CURRENT_ASSETS = "ifrs-full_CurrentAssets";
    private static final String ACCOUNT_ID_CURRENT_LIABILITIES = "ifrs-full_CurrentLiabilities";
    private static final String ACCOUNT_ID_CASH = "ifrs-full_CashAndCashEquivalents";
    private static final String ACCOUNT_ID_ISSUED_CAPITAL = "ifrs-full_IssuedCapital";
    private static final String ACCOUNT_ID_RETAINED_EARNINGS = "ifrs-full_RetainedEarnings";
    private static final String ACCOUNT_ID_EQUITY_OWNERS = "ifrs-full_EquityAttributableToOwnersOfParent";
    private static final String ACCOUNT_ID_OPERATING_CASH_FLOW = "ifrs-full_CashFlowsFromUsedInOperatingActivities";
    private static final String ACCOUNT_ID_INVESTING_CASH_FLOW = "ifrs-full_CashFlowsFromUsedInInvestingActivities";
    private static final String ACCOUNT_ID_FINANCING_CASH_FLOW = "ifrs-full_CashFlowsFromUsedInFinancingActivities";
    // 금융업 판별용 — 매출액 대신 이 계정들로 영업수익을 공시한다(KB금융·신한지주·삼성생명·삼성화재 등 실응답 확인).
    private static final java.util.Set<String> FINANCIAL_REVENUE_ACCOUNT_IDS = java.util.Set.of(
            "ifrs-full_RevenueFromInterest", "ifrs-full_InsuranceRevenue", "dart_OperatingIncomeInsurance",
            "ifrs-full_FeeAndCommissionIncome");

    // corpCode.xml 압축을 풀면 나오는 내부 파일명 — DART가 고정으로 이 이름을 쓴다.
    private static final String CORP_CODE_ENTRY_NAME = "CORPCODE.xml";

    private final RestClient restClient;

    @Value("${app.dart.api-key}")
    private String apiKey;

    @Value("${app.dart.api-url}")
    private String apiUrl;

    @Value("${app.dart.corp-code-url}")
    private String corpCodeUrl;

    // 회사명 → corp_code 캐시. 최초 조회 시점에 한 번만 채워지며, 이후로는 이 필드를 그대로 읽는다.
    // 여러 요청 스레드가 동시에 처음 접근할 수 있어 volatile + synchronized 초기화로 보호한다.
    private volatile Map<String, String> corpCodeByName;

    // 회사명 → KRX 종목코드(stock_code) 캐시. corp_code와 같은 DART 고유번호 목록(zip) 안에
    // 함께 들어있는 값이라, 별도로 다운로드하지 않고 corpCodeByName과 한 번의 파싱으로 같이
    // 채운다 — AI 상담의 get_current_price 도구가 외부 시세 데이터 제공사 실시간 시세 캐시(Redis
    // stock:price:{stockCode})를 조회할 stockCode를 여기서 얻는다.
    private volatile Map<String, String> stockCodeByName;
    private volatile Map<String, String> corpCodeByStockCode;

    // 2026-08-13 추가 — corpCodeByName/stockCodeByName과 동일한 데이터를 정규화된 키(대소문자
    // 무시 + "주식회사"/"(주)" 등 법인 접미사 제거)로도 인덱싱해둔 보조 캐시. "LG"/"lg"/
    // "LG주식회사"처럼 정확히 같은 문자열이 아니어도 찾을 수 있게 하기 위함(라이브 테스트로
    // "SK쉴더스"→DART 원본은 "에스케이쉴더스"로만 등록돼 있어 정확 일치로는 못 찾던 문제가
    // 실측됨 — 이건 접미사/대소문자가 아니라 영문↔한글 발음표기 문제라 GROUP_NAME_ALIASES로
    // 별도 처리).
    private volatile Map<String, String> normalizedCorpCodeByName;
    private volatile Map<String, String> normalizedStockCodeByName;

    // 국내 주요 그룹의 영문 약칭↔한글 발음표기 대응표. DART 원본 corp_name이 어느 쪽으로
    // 등록돼 있는지 회사마다 달라(예: "SK하이닉스"는 영문 그대로, "에스케이쉴더스"는 한글
    // 표기) 양방향으로 시도해봐야 한다. 확인된 것만 보수적으로 등록 — 뉴스 검색의
    // NewsRelevanceMatcher.KNOWN_ALIASES와 같은 원칙(추측으로 새 항목을 늘리지 않는다).
    private static final Map<String, String> GROUP_NAME_ALIASES = Map.of(
            "SK", "에스케이",
            "LG", "엘지",
            "GS", "지에스",
            "CJ", "씨제이",
            "KT", "케이티");

    public DartApiClient(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
    }

    // 연간 재무제표(사업보고서, request.year() 기준) 조회 — "전반적인 재무 상태"를 물을 때 쓴다.
    public DartFinancialResponse getFinancials(DartFinancialRequest request) {
        DartFinancialResponse response = fetchFinancials(request.corpCode(), request.year(), REPORT_CODE_BUSINESS);
        return response != null ? response : emptyResponse(request.corpCode(), request.year());
    }

    /**
     * 가장 최근에 실제로 공시됐을 법한 분기 실적을 조회한다 — "최근 실적 어때요?"처럼 최신
     * 성과를 물을 때 연간 사업보고서보다 이게 더 적합하다(사업연도가 끝난 다음 해에야 공시되는
     * 연간과 달리, 분기 보고서는 분기가 끝나고 45일 이내 공시되어 훨씬 최신이다).
     *
     * <p>"3분기→반기→1분기를 무조건 순서대로 다 찔러본다"는 방식은 지금이 어느 시점이든 상관없이
     * 최대 3개 보고서 종류 × CFS/OFS 2번 = 최악의 경우 DART를 6번 호출하는 낭비가 있었다(예:
     * 8월 초에는 3분기·반기 보고서가 애초에 존재할 수 없는 시점인데도 그 둘을 먼저 헛되이
     * 시도한 뒤에야 실제로 있는 1분기를 찾는 식). 그래서 공시 기한 규칙(분기·반기는 마감 후
     * 45일, 이 프로젝트에서는 여유 있게 하루 더한 46일 기준)으로 "오늘 시점에 실제로 존재할
     * 가능성이 높은 보고서"부터 먼저 시도한다 — 대부분의 경우 이 한 번으로 끝난다.
     *
     * <p>다만 그 보고서에 데이터가 없으면(공시가 예정보다 늦어진 회사 등), 무작정 작년 연간으로
     * 건너뛰지 않고 "이미 기한이 지나 있어 존재할 수 있는" 같은 해의 더 이전 분기(예: 3분기를
     * 시도했다가 없으면 반기, 반기도 없으면 1분기)를 순서대로 마저 시도한다 — 아직 기한이 안 된
     * 이후 분기(예: 반기를 추정했는데 3분기)는 애초에 존재할 수 없으므로 시도하지 않는다. 그래도
     * 못 찾으면 마지막 안전장치로 작년 연간 사업보고서를 시도한다.</p>
     */
    public DartFinancialResponse getRecentQuarterlyFinancials(String corpCode) {
        ReportPeriod likelyAvailable = mostRecentLikelyAvailableQuarter(LocalDate.now(KST));
        int startIndex = QUARTERLY_REPORT_CODES_NEWEST_FIRST.indexOf(likelyAvailable.reportCode());
        for (int i = startIndex; i < QUARTERLY_REPORT_CODES_NEWEST_FIRST.size(); i++) {
            DartFinancialResponse response = fetchFinancials(corpCode, likelyAvailable.year(), QUARTERLY_REPORT_CODES_NEWEST_FIRST.get(i));
            if (response != null) {
                return response;
            }
        }

        int fallbackYear = LocalDate.now(KST).getYear() - ANNUAL_FALLBACK_YEAR_OFFSET;
        DartFinancialResponse fallback = fetchFinancials(corpCode, fallbackYear, REPORT_CODE_BUSINESS);
        return fallback != null ? fallback : emptyResponse(corpCode, fallbackYear);
    }

    // 날짜는 한국 시간 기준 — 배포 서버(UTC)에서 1월 1일 00~09시(KST)에 "작년"을 1년 잘못 계산하지 않게(2026-10-12 코드리뷰).
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    // 4월 1일~5월 15일에는 3월 말에 나온 작년 사업보고서가 작년 3분기 보고서보다 최신이다 — 이 기간엔 사업보고서를 먼저 찾는다
    // (5월 16일부터는 올해 1분기 보고서가 기한을 넘겨 더 최신). package-private: 날짜 경계값 테스트용.
    boolean isAnnualReportFirst(LocalDate today) {
        return !today.isBefore(LocalDate.of(today.getYear(), 4, 1)) && today.isBefore(LocalDate.of(today.getYear(), 5, 16));
    }

    // 최신순(3분기→반기→1분기) 고정 순서 — mostRecentLikelyAvailableQuarter()가 고른 시작 지점부터
    // 이 리스트를 앞으로 훑으면 "이미 기한이 지난, 더 이전 분기"만 시도하게 된다.
    private static final List<String> QUARTERLY_REPORT_CODES_NEWEST_FIRST =
            List.of(REPORT_CODE_Q3, REPORT_CODE_HALF, REPORT_CODE_Q1);

    // 분기·반기 보고서는 법정 제출기한이 분기 종료 후 45일 이내다 — 하루 여유를 둬 46일 기준으로
    // "이미 공시 기한이 지나 존재할 가능성이 높은" 가장 최근 보고서를 고른다. 올해 어떤
    // 분기도 아직 기한이 안 지났다면(1~5월 중순) 작년 3분기가 그 시점 기준 가장 최근 보고서다
    // (작년 3분기 기한은 작년 11월 중순이라 올해 어느 시점이든 이미 지나 있음이 보장된다).
    // package-private: DartApiClientTest가 LocalDate.now(KST)에 의존하지 않고 날짜 경계값을
    // 직접 넣어 이 순수 로직만 검증할 수 있게 한다.
    ReportPeriod mostRecentLikelyAvailableQuarter(LocalDate today) {
        int year = today.getYear();
        if (!today.isBefore(LocalDate.of(year, 11, 15))) {
            return new ReportPeriod(year, REPORT_CODE_Q3);
        }
        if (!today.isBefore(LocalDate.of(year, 8, 15))) {
            return new ReportPeriod(year, REPORT_CODE_HALF);
        }
        if (!today.isBefore(LocalDate.of(year, 5, 16))) {
            return new ReportPeriod(year, REPORT_CODE_Q1);
        }
        return new ReportPeriod(year - 1, REPORT_CODE_Q3);
    }

    // package-private: 위 mostRecentLikelyAvailableQuarter()와 동일한 이유로 테스트 접근 허용.
    record ReportPeriod(int year, String reportCode) {
    }

    // ===== 공시 상세 조회(자본변동/소유권 현황) =====
    //
    // 재무제표(fnlttSinglAcntAll)는 응답 항목이 고정 6개라 DartFinancialResponse라는 전용 타입을
    // 썼지만, 아래 공시 API들은 API마다 응답 필드가 제각각이고(유상증자는 자금조달 목적별 금액,
    // 최대주주현황은 지분율 등) 종류도 계속 늘어날 예정이라, API마다 전용 DTO를 새로 만들지 않고
    // "각 항목을 이름→값 Map으로" 받아서 executeXxxLookup()이 필요한 키만 꺼내 문장으로
    // 조립하는 방식을 쓴다. 응답 자체는 재무제표와 동일하게 {"status", "message", "list":[...]}
    // 구조를 공유하므로 파싱 로직(fetchDisclosureList)도 공용으로 쓸 수 있다.
    private static final String ENDPOINT_PAID_CAPITAL_INCREASE = "piicDecsn.json";   // 유상증자 결정
    private static final String ENDPOINT_FREE_CAPITAL_INCREASE = "fricDecsn.json";   // 무상증자 결정
    private static final String ENDPOINT_MAJOR_HOLDER_STATUS = "hyslrSttus.json";    // 최대주주 현황
    private static final String ENDPOINT_MAJOR_HOLDER_CHANGE = "hyslrChgSttus.json"; // 최대주주 변동현황

    // 자본변동(유상증자/무상증자) 공시는 재무제표와 달리 "사업연도+보고서종류"가 아니라
    // "조회 기간(시작일~종료일)"으로 검색한다 — 특정 분기가 아니라 특정 기간 안에 있었던
    // 이벤트를 전부 찾는 방식이라서다. "최근"이 기본 의도이므로 2년치를 기본 조회 기간으로 둔다.
    private static final int CAPITAL_CHANGE_LOOKBACK_YEARS = 2;
    // 한 회사가 짧은 기간에 같은 종류 공시를 여러 번 낼 수 있어(예: 여러 차례 유상증자), 프롬프트가
    // 과도하게 길어지지 않도록 최신순으로 이 개수까지만 답변에 담는다.
    private static final int MAX_DISCLOSURE_ITEMS = 5;

    /**
     * 자본변동(유상증자/무상증자 결정) 공시를 조회한다. changeType이 정확히 "무상증자"일 때만
     * 무상증자로 조회하고, 그 외(정확히 "유상증자"이거나 애매한 값)는 유상증자로 조회한다 —
     * "애매하면 유상증자가 기본"이라는 판단은 호출부(AiPlanningService.executeCapitalChangeLookup())가
     * 이미 내린 뒤 정확한 문자열로 넘겨주므로, 여기서는 그 결정을 그대로 반영할 뿐이다.
     */
    public List<Map<String, Object>> getCapitalChangeDecisions(String corpCode, String changeType) {
        String endpoint = "유상증자".equals(changeType) ? ENDPOINT_PAID_CAPITAL_INCREASE : ENDPOINT_FREE_CAPITAL_INCREASE;
        LocalDate today = LocalDate.now(KST);
        Map<String, String> params = Map.of(
                "bgn_de", today.minusYears(CAPITAL_CHANGE_LOOKBACK_YEARS).format(DATE_FORMAT),
                "end_de", today.format(DATE_FORMAT));
        return fetchDisclosureList(endpoint, corpCode, params);
    }

    /**
     * 소유권(최대주주 현황/변동현황) 공시를 조회한다. infoType이 "변동"이면 변동현황을, 그 외에는
     * 현황(스냅샷)을 조회한다. 이 두 API는 재무제표처럼 사업연도+보고서종류 기준이라, 이미 만들어둔
     * mostRecentLikelyAvailableQuarter()를 그대로 재사용해 "최근에 실제로 존재할 법한" 시점을 고른다.
     */
    public List<Map<String, Object>> getOwnershipInfo(String corpCode, String infoType) {
        String endpoint = "변동".equals(infoType) ? ENDPOINT_MAJOR_HOLDER_CHANGE : ENDPOINT_MAJOR_HOLDER_STATUS;
        return fetchYearReportWithAnnualFallback(endpoint, corpCode);
    }

    private static final java.time.format.DateTimeFormatter DATE_FORMAT = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd");

    // ===== 범용 공시 조회(그 외 70여 개 DART API) =====
    //
    // DART 개발가이드 전체(공시정보/정기보고서/지분공시/주요사항보고서/증권신고서 6개 섹션,
    // https://opendart.fss.or.kr/guide/main.do)에는 재무제표·자본변동·소유권 외에도 배당, 임원
    // 현황, 회사채 발행, 소송, 합병·분할, 부도 등 수십 개의 조회 API가 더 있다. 이걸 위 두
    // 메서드처럼 하나씩 전용 메서드로 만들면 API 하나 늘 때마다 코드가 하나씩 늘어나는 구조라
    // 확장성이 없다 — 대신 "이름표(라벨) → (엔드포인트, 파라미터 방식)"을 표 하나(DISCLOSURE_REGISTRY)로
    // 관리하고, AI가 그 이름표 중 하나를 골라 넘기면 표를 찾아 호출하는 범용 메서드 하나로
    // 처리한다. 응답 항목도 API마다 필드가 다 달라 전용 포맷터를 안 만들고, AiPlanningService가
    // 식별용 필드(회사코드 등)만 제외하고 나머지를 그대로 나열하는 범용 방식으로 처리한다.
    enum DisclosureParamStyle {
        // 정기보고서류 — 사업연도+보고서종류(연간/분기) 기준
        YEAR_REPORT,
        // 주요사항보고서·증권신고서류 — 조회기간(시작일~종료일) 기준, 특정 사건을 찾는 방식
        DATE_RANGE,
        // 지분공시류 — 기간·연도 지정 없이 회사 코드만으로 조회(현재 시점 스냅샷)
        CORP_CODE_ONLY,
    }

    // description은 사용자가 압축된 전문용어("채무증권미상환잔액" 등)를 그대로 말하지 않고
    // "이 회사 빚 많아요?"처럼 애매하게 물어도 Gemini가 뜻을 보고 알맞은 라벨을 고를 수 있게
    // 하기 위한 쉬운 한 줄 설명이다(DART 개발가이드의 "뜻" 문구를 그대로 옮김). 라벨만 있고
    // 설명이 없으면 이런 애매한 질문에서 AI가 어느 라벨을 골라야 할지 판단하기 어렵다.
    record DisclosureType(String endpoint, DisclosureParamStyle paramStyle, String description) {
    }

    private static final Map<String, DisclosureType> DISCLOSURE_REGISTRY = buildDisclosureRegistry();

    private static Map<String, DisclosureType> buildDisclosureRegistry() {
        Map<String, DisclosureType> registry = new java.util.LinkedHashMap<>();
        DisclosureParamStyle yearReport = DisclosureParamStyle.YEAR_REPORT;
        DisclosureParamStyle dateRange = DisclosureParamStyle.DATE_RANGE;
        DisclosureParamStyle corpCodeOnly = DisclosureParamStyle.CORP_CODE_ONLY;

        // --- 정기보고서 주요정보 (YEAR_REPORT) ---
        registry.put("주식총수현황", new DisclosureType("stockTotqySttus.json", yearReport, "회사가 발행한 주식의 총 수량 현황"));
        registry.put("자기주식취득처분현황", new DisclosureType("tesstkAcqsDspsSttus.json", yearReport, "회사가 자기주식(자사주)을 사고판 내역"));
        registry.put("배당사항", new DisclosureType("alotMatter.json", yearReport, "배당금 규모, 배당률 등 배당 관련 정보"));
        registry.put("증자감자현황", new DisclosureType("irdsSttus.json", yearReport, "주식을 늘리거나(증자) 줄인(감자) 이력"));
        registry.put("채무증권발행실적", new DisclosureType("detScritsIsuAcmslt.json", yearReport, "회사채 등 빚 성격의 증권을 발행한 실적"));
        registry.put("기업어음미상환잔액", new DisclosureType("entrprsBilScritsNrdmpBlce.json", yearReport, "아직 갚지 않은 기업어음(CP)의 잔액"));
        registry.put("단기사채미상환잔액", new DisclosureType("srtpdPsndbtNrdmpBlce.json", yearReport, "아직 갚지 않은 단기사채의 잔액"));
        registry.put("회사채미상환잔액", new DisclosureType("cprndNrdmpBlce.json", yearReport, "아직 갚지 않은 회사채의 잔액"));
        registry.put("신종자본증권미상환잔액", new DisclosureType("newCaplScritsNrdmpBlce.json", yearReport, "자본으로 인정되는 특수채권(영구채 등)의 미상환 잔액"));
        registry.put("조건부자본증권미상환잔액", new DisclosureType("cndlCaplScritsNrdmpBlce.json", yearReport, "코코본드 등 특정조건에서 상각되는 자본증권의 미상환 잔액"));
        registry.put("공모자금사용내역", new DisclosureType("pssrpCptalUseDtls.json", yearReport, "일반투자자 대상 공모로 모은 돈을 어디에 썼는지 내역"));
        registry.put("사모자금사용내역", new DisclosureType("prvsrpCptalUseDtls.json", yearReport, "특정 소수 투자자(사모)로 모은 돈을 어디에 썼는지 내역"));
        registry.put("회계감사인감사의견", new DisclosureType("accnutAdtorNmNdAdtOpinion.json", yearReport, "감사를 맡은 회계법인 이름과 감사의견(적정/한정 등)"));
        registry.put("감사용역체결현황", new DisclosureType("adtServcCnclsSttus.json", yearReport, "감사계약 체결 내역(감사보수, 소요시간 등)"));
        registry.put("비감사용역계약현황", new DisclosureType("accnutAdtorNonAdtServcCnclsSttus.json", yearReport, "감사 외 별도로 컨설팅 등 용역을 계약한 내역"));
        registry.put("사외이사변동현황", new DisclosureType("outcmpnyDrctrNdChangeSttus.json", yearReport, "사외이사 명단과 교체 이력"));
        registry.put("소액주주현황", new DisclosureType("mrhlSttus.json", yearReport, "소액주주 수와 그들이 보유한 지분비율"));
        registry.put("임원현황", new DisclosureType("exctvSttus.json", yearReport, "등기임원(대표이사, 이사 등) 명단과 정보"));
        registry.put("직원현황", new DisclosureType("empSttus.json", yearReport, "직원 수, 평균근속연수, 평균급여 등 정보"));
        registry.put("미등기임원보수현황", new DisclosureType("unrstExctvMendngSttus.json", yearReport, "등기되지 않은 임원(집행임원 등)의 보수 현황"));
        registry.put("이사감사보수승인금액", new DisclosureType("drctrAdtAllMendngSttusGmtsckConfmAmount.json", yearReport, "주주총회에서 승인된 이사·감사 보수 한도액"));
        registry.put("이사감사보수지급총액", new DisclosureType("hmvAuditAllSttus.json", yearReport, "실제로 지급된 이사·감사 보수 총액"));
        registry.put("이사감사보수유형별", new DisclosureType("drctrAdtAllMendngSttusMendngPymntamtTyCl.json", yearReport, "급여, 상여 등 유형별로 나눈 이사·감사 보수 지급내역"));
        registry.put("개인별보수현황5억이상", new DisclosureType("hmvAuditIndvdlBySttus.json", yearReport, "5억원 이상 받은 임원 개인별 보수 공개"));
        registry.put("개인별보수상위5인", new DisclosureType("indvdlByPay.json", yearReport, "5억원 이상 받는 임원 중 상위 5명만 추린 개인별 보수"));
        registry.put("타법인출자현황", new DisclosureType("otrCprInvstmntSttus.json", yearReport, "다른 회사에 지분투자(출자)한 현황"));

        // --- 정기보고서 재무정보 (YEAR_REPORT, 단일회사 한정 — 다중회사 비교 API는 이번 범위 밖) ---
        registry.put("단일회사주요계정", new DisclosureType("fnlttSinglAcnt.json", yearReport, "한 회사의 핵심 재무계정(매출액, 영업이익, 자산총계 등)"));
        registry.put("단일회사재무지표", new DisclosureType("fnlttSinglIndx.json", yearReport, "한 회사의 재무비율(부채비율, ROE 등 수익성·안정성 지표)"));

        // --- 지분공시 종합정보 (CORP_CODE_ONLY) ---
        registry.put("임원주요주주소유보고", new DisclosureType("elestock.json", corpCodeOnly, "임원이나 주요주주(10% 이상 보유 등)가 자기 회사 주식을 사고판 내역"));
        registry.put("대량보유상황보고", new DisclosureType("majorstock.json", corpCodeOnly, "특정 주식을 5% 이상 보유하게 된 투자자가 공시하는 정보(5%룰)"));

        // --- 주요사항보고서 주요정보 (DATE_RANGE) ---
        registry.put("부도발생", new DisclosureType("dfOcr.json", dateRange, "회사가 어음·수표를 만기에 결제하지 못해 부도가 난 것"));
        registry.put("주권관련사채권양도결정", new DisclosureType("stkrtbdTrfDecsn.json", dateRange, "전환사채(CB) 등 주식 관련 채권을 다른 곳에 팔기로 결정하는 것"));
        registry.put("회생절차개시신청", new DisclosureType("ctrcvsBgrq.json", dateRange, "사실상 파산 위기, 법원에 회사 살려달라고 구제 신청하는 것"));
        registry.put("해산사유발생", new DisclosureType("dsRsOcr.json", dateRange, "회사가 문을 닫아야 할 법적 사유가 생긴 것"));
        registry.put("유무상증자결정", new DisclosureType("pifricDecsn.json", dateRange, "유상증자와 무상증자를 동시에 진행하는 것"));
        registry.put("감자결정", new DisclosureType("crDecsn.json", dateRange, "회사가 발행주식 수를 줄이는 것(보통 부실기업 구조조정용)"));
        registry.put("채권은행관리절차개시", new DisclosureType("bnkMngtPcbg.json", dateRange, "회사가 부실해져서 채권은행이 경영을 감시·관리하기 시작하는 것(워크아웃)"));
        registry.put("소송제기", new DisclosureType("lwstLg.json", dateRange, "회사가 소송을 당하거나, 회사가 남에게 소송을 거는 것"));
        registry.put("해외상장결정", new DisclosureType("ovLstDecsn.json", dateRange, "해외 증권시장에 주식을 상장하기로 결정한 것"));
        registry.put("해외상장폐지결정", new DisclosureType("ovDlstDecsn.json", dateRange, "해외 증권시장에서 상장을 폐지하기로 결정한 것"));
        registry.put("해외상장", new DisclosureType("ovLst.json", dateRange, "해외 증권시장에 실제로 주식이 상장된 것"));
        registry.put("해외상장폐지", new DisclosureType("ovDlst.json", dateRange, "해외 증권시장에서 실제로 상장이 폐지된 것"));
        registry.put("전환사채발행결정", new DisclosureType("cvbdIsDecsn.json", dateRange, "나중에 주식으로 바꿀 수 있는 채권(CB)을 발행하기로 결정"));
        registry.put("신주인수권부사채발행결정", new DisclosureType("bdwtIsDecsn.json", dateRange, "새 주식을 살 수 있는 권리가 붙은 채권(BW)을 발행하기로 결정"));
        registry.put("교환사채발행결정", new DisclosureType("exbdIsDecsn.json", dateRange, "다른 회사 주식으로 바꿀 수 있는 채권(EB)을 발행하기로 결정"));
        registry.put("채권은행관리절차중단", new DisclosureType("bnkMngtPcsp.json", dateRange, "채권은행의 경영 감시·관리(워크아웃)가 끝나는 것"));
        registry.put("조건부자본증권발행결정", new DisclosureType("wdCocobdIsDecsn.json", dateRange, "특정 조건(부실화 등) 발생 시 원금이 사라질 수 있는 특수 채권을 발행하기로 결정"));
        registry.put("자산양수도풋백옵션", new DisclosureType("astInhtrfEtcPtbkOpt.json", dateRange, "기타 자산을 사고팔거나, 되팔 수 있는 권리(풋백옵션)를 포함한 거래"));
        registry.put("타법인증권양도결정", new DisclosureType("otcprStkInvscrTrfDecsn.json", dateRange, "다른 회사의 주식·지분을 팔기로 결정하는 것"));
        registry.put("유형자산양도결정", new DisclosureType("tgastTrfDecsn.json", dateRange, "부동산, 공장, 설비 등 눈에 보이는 자산을 팔기로 결정"));
        registry.put("유형자산양수결정", new DisclosureType("tgastInhDecsn.json", dateRange, "부동산, 공장, 설비 등을 사기로 결정"));
        registry.put("타법인증권양수결정", new DisclosureType("otcprStkInvscrInhDecsn.json", dateRange, "다른 회사의 주식·지분을 사기로 결정하는 것"));
        registry.put("영업양도결정", new DisclosureType("bsnTrfDecsn.json", dateRange, "회사의 특정 사업부문을 통째로 팔기로 결정"));
        registry.put("영업양수결정", new DisclosureType("bsnInhDecsn.json", dateRange, "다른 회사의 사업부문을 통째로 사기로 결정"));
        registry.put("자사주신탁해지결정", new DisclosureType("tsstkAqTrctrCcDecsn.json", dateRange, "자사주 매입을 위탁했던 신탁계약을 해지하는 것"));
        registry.put("자사주신탁체결결정", new DisclosureType("tsstkAqTrctrCnsDecsn.json", dateRange, "자사주 매입을 증권사 등에 위탁하는 신탁계약을 새로 맺는 것"));
        registry.put("자사주처분결정", new DisclosureType("tsstkDpDecsn.json", dateRange, "회사가 보유 중이던 자사주를 시장에 팔기로 결정"));
        registry.put("자사주취득결정", new DisclosureType("tsstkAqDecsn.json", dateRange, "회사가 자기 회사 주식을 직접 사들이기로 결정(주가부양 목적)"));
        registry.put("주식교환이전결정", new DisclosureType("stkExtrDecsn.json", dateRange, "지주회사 전환 등을 위해 주주들의 주식을 다른 회사 주식과 맞바꾸는 것"));
        registry.put("회사분할합병결정", new DisclosureType("cmpDvmgDecsn.json", dateRange, "회사를 분할하면서 동시에 다른 회사와 합병하는 것(단순 흡수합병이 아니라 분할+합병이 함께 일어나는 경우만 해당)"));
        registry.put("회사분할결정", new DisclosureType("cmpDvDecsn.json", dateRange, "하나의 회사를 둘 이상으로 쪼개는 것(합병 없이 분할만 하는 경우)"));
        registry.put("회사합병결정", new DisclosureType("cmpMgDecsn.json", dateRange, "두 회사가 하나의 회사로 합쳐지는 것 — '인수합병'·'M&A'처럼 합병 여부 자체를 묻는 일반적인 질문엔 우선 이 라벨부터 확인한다"));
        registry.put("사채권양수결정", new DisclosureType("stkrtbdInhDecsn.json", dateRange, "전환사채 등 주식 관련 채권을 사들이기로 결정하는 것"));
        registry.put("영업정지", new DisclosureType("bsnSp.json", dateRange, "관할 기관으로부터 사업 일부 또는 전체를 중단하라는 명령을 받는 것"));

        // --- 증권신고서 주요정보 (DATE_RANGE) ---
        registry.put("주식교환이전증권신고서", new DisclosureType("extrRs.json", dateRange, "지주회사 전환 등을 위해 주주들의 주식을 통째로 맞바꾸는 것을 신고하는 문서"));
        registry.put("합병증권신고서", new DisclosureType("mgRs.json", dateRange, "합병으로 신주를 발행해 증권을 모집·매출할 때만 제출하는 신고서 — 무증자합병이면 이 신고서 자체가 없는 게 정상이므로, 합병 여부를 확인할 땐 '회사합병결정'을 함께 봐야 한다"));
        registry.put("분할증권신고서", new DisclosureType("dvRs.json", dateRange, "분할로 신주를 발행해 증권을 모집·매출할 때만 제출하는 신고서 — '회사분할결정'과 함께 봐야 한다"));
        registry.put("채무증권신고서", new DisclosureType("bdRs.json", dateRange, "회사채 등 빚 성격의 증권을 발행할 때 제출하는 신고서"));
        registry.put("지분증권신고서", new DisclosureType("estkRs.json", dateRange, "주식 등 지분 성격의 증권을 발행할 때 제출하는 신고서"));
        registry.put("증권예탁증권신고서", new DisclosureType("stkdpRs.json", dateRange, "해외에서 국내주식 대신 거래되는 증서(ADR/GDR 등) 발행 신고"));

        return Map.copyOf(registry);
    }

    // AiPlanningService가 도구 설명(description)에 "라벨(뜻)" 형태로 유효한 disclosureType
    // 목록을 나열할 때 쓴다. DISCLOSURE_REGISTRY가 인스턴스 상태와 무관한 static 데이터라
    // 인스턴스 없이도 호출 가능하게 static으로 열어둔다 — AiPlanningService의 static final
    // 도구 선언 초기화 시점에 바로 쓸 수 있다.
    public static List<String> getSupportedDisclosureTypesWithDescription() {
        return DISCLOSURE_REGISTRY.entrySet().stream()
                .map(entry -> entry.getKey() + "(" + entry.getValue().description() + ")")
                .toList();
    }

    /**
     * DISCLOSURE_REGISTRY에 등록된 라벨 중 하나로 해당 공시를 조회한다. 등록되지 않은 라벨이 오면
     * (Gemini가 목록에 없는 값을 잘못 만들어낸 경우) 예외 없이 빈 리스트를 반환한다 — 다른
     * 조회들과 동일하게 "찾지 못했다"로 자연스럽게 처리되도록.
     */
    public List<Map<String, Object>> getDisclosureInfo(String corpCode, String disclosureType) {
        DisclosureType type = DISCLOSURE_REGISTRY.get(disclosureType);
        if (type == null) {
            log.warn("등록되지 않은 disclosureType 요청 - disclosureType: {}", disclosureType);
            return List.of();
        }

        // fnlttSinglIndx.json(단일회사재무지표)만 다른 YEAR_REPORT류와 달리 idx_cl_code(지표
        // 분류코드)가 추가로 필수라, 나머지 26개와 같은 방식으로 부르면 안 된다 — 전체 70개
        // 항목을 실제로 호출해 검증하다 발견함(2026-08-05). idx_cl_code 없이 부르면 DART가
        // status "100"(필수값 누락)으로 거부한다.
        if (FNLTT_SINGL_INDX_ENDPOINT.equals(type.endpoint())) {
            return fetchFinancialIndicators(corpCode);
        }

        if (type.paramStyle() == DisclosureParamStyle.YEAR_REPORT) {
            return fetchYearReportWithAnnualFallback(type.endpoint(), corpCode);
        }
        Map<String, String> params = switch (type.paramStyle()) {
            case DATE_RANGE -> {
                LocalDate today = LocalDate.now(KST);
                yield Map.of(
                        "bgn_de", today.minusYears(CAPITAL_CHANGE_LOOKBACK_YEARS).format(DATE_FORMAT),
                        "end_de", today.format(DATE_FORMAT));
            }
            case CORP_CODE_ONLY -> Map.of();
            case YEAR_REPORT -> throw new IllegalStateException("YEAR_REPORT는 위에서 이미 처리됨");
        };
        return fetchDisclosureList(type.endpoint(), corpCode, params);
    }

    // 정기보고서 주요정보류(직원현황, 개인별보수 등 26개 항목)는 분기·반기 보고서에는 행은
    // 내려오되 실질 값이 전부 빈칸이고, 사업보고서(연간)에만 실제 데이터가 실리는 경우가 많다
    // — 실제 라이브 테스트로 확인된 사례(NAVER 직원현황: 분기로 조회하면 stlm_dt만 채워진 빈
    // 행 1개, 연간으로 다시 조회하면 성별·인원·평균급여 등 실데이터). getRecentQuarterlyFinancials()와
    // 동일한 패턴으로, 먼저 최근 분기로 시도하고 실질 데이터가 없으면 최근 연간(사업보고서)으로
    // 한 번 더 시도한다. 소유권(getOwnershipInfo)과 범용 공시 조회(getDisclosureInfo)가 공유한다.
    private List<Map<String, Object>> fetchYearReportWithAnnualFallback(String endpoint, String corpCode) {
        ReportPeriod period = mostRecentLikelyAvailableQuarter(LocalDate.now(KST));
        List<Map<String, Object>> quarterlyItems = fetchDisclosureList(endpoint, corpCode, Map.of(
                "bsns_year", String.valueOf(period.year()),
                "reprt_code", period.reportCode()));
        if (!quarterlyItems.isEmpty() && !isBlankOfContent(quarterlyItems, YEAR_REPORT_BOILERPLATE_KEYS)) {
            return quarterlyItems;
        }

        int fallbackYear = LocalDate.now(KST).getYear() - ANNUAL_FALLBACK_YEAR_OFFSET;
        return fetchDisclosureList(endpoint, corpCode, Map.of(
                "bsns_year", String.valueOf(fallbackYear),
                "reprt_code", REPORT_CODE_BUSINESS));
    }

    private static final String FNLTT_SINGL_INDX_ENDPOINT = "fnlttSinglIndx.json";

    // fnlttSinglIndx.json은 지표가 수익성/안정성/성장성/활동성 4개 카테고리(idx_cl_code)로
    // 나뉘어 있어 한 번 호출에 한 카테고리만 내려온다. DISCLOSURE_REGISTRY의 "단일회사재무지표"
    // description이 약속한 "부채비율, ROE 등 수익성·안정성 지표"에 맞춰 이 두 카테고리만
    // 합쳐서 반환한다(실제 값으로 확인, 2026-08-05: M210000=수익성지표, M220000=안정성지표).
    private static final List<String> FINANCIAL_INDEX_CATEGORY_CODES = List.of("M210000", "M220000");

    private List<Map<String, Object>> fetchFinancialIndicators(String corpCode) {
        ReportPeriod period = mostRecentLikelyAvailableQuarter(LocalDate.now(KST));
        List<Map<String, Object>> quarterlyItems = fetchFinancialIndicatorCategories(
                corpCode, String.valueOf(period.year()), period.reportCode());
        if (!quarterlyItems.isEmpty() && !isBlankOfContent(quarterlyItems, YEAR_REPORT_BOILERPLATE_KEYS)) {
            return quarterlyItems;
        }

        int fallbackYear = LocalDate.now(KST).getYear() - ANNUAL_FALLBACK_YEAR_OFFSET;
        return fetchFinancialIndicatorCategories(corpCode, String.valueOf(fallbackYear), REPORT_CODE_BUSINESS);
    }

    // 두 카테고리(M210000/M220000)는 서로 독립적인 DART 호출이라 순차 대신 동시에 실행한다.
    // 이 메서드는 AiPlanningService.executeTool()이 aiToolTaskExecutor(고정 크기 스레드풀) 위에서
    // 실행하는 도중에 호출될 수 있어, 같은 고정 크기 풀에 또 작업을 맡기면 중첩으로 인한 스레드
    // 고갈 위험이 있다 — 가상 스레드(Java 21)는 풀 크기 제한이 없어 이런 중첩 팬아웃에 안전하다.
    private List<Map<String, Object>> fetchFinancialIndicatorCategories(String corpCode, String bsnsYear, String reprtCode) {
        try (var virtualExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<List<Map<String, Object>>>> futures = FINANCIAL_INDEX_CATEGORY_CODES.stream()
                    .map(idxClCode -> CompletableFuture.supplyAsync(
                            () -> fetchDisclosureList(FNLTT_SINGL_INDX_ENDPOINT, corpCode, Map.of(
                                    "bsns_year", bsnsYear,
                                    "reprt_code", reprtCode,
                                    "idx_cl_code", idxClCode)),
                            virtualExecutor))
                    .toList();
            List<Map<String, Object>> combined = new java.util.ArrayList<>();
            futures.forEach(future -> combined.addAll(future.join()));
            return combined;
        }
    }

    // 식별용/날짜용 필드(비상장 회사라도 항상 채워져 오는 필드)를 제외한 나머지가 모든 행에서
    // 전부 null/빈값/"-"이면 true — "행은 왔지만 실질 데이터가 없다"를 판정한다. DART 응답의
    // 필드 이름(rcept_no, corp_code 등 원본 영문 축약어)은 DART API 자체의 특성이라 어떤
    // 필드가 boilerplate인지도 이 infra 클라이언트가 알고 있는 게 맞다 — AiPlanningService는
    // isBlankOfContent()를 그대로 호출해 재사용하고, 조회 종류별 boilerplate 키 집합만 아래
    // 상수로 여기서 함께 관리한다.
    private static final java.util.Set<String> YEAR_REPORT_BOILERPLATE_KEYS =
            java.util.Set.of("rcept_no", "corp_cls", "corp_code", "corp_name", "stock_code", "stock_knd", "stlm_dt");

    // 최대주주 현황/변동현황(getOwnershipInfo) 응답의 boilerplate 키. AiPlanningService.executeOwnershipLookup()이 사용.
    public static final java.util.Set<String> OWNERSHIP_BOILERPLATE_KEYS =
            java.util.Set.of("rcept_no", "corp_cls", "corp_code", "corp_name", "stock_knd", "stlm_dt", "change_on");

    // 범용 공시 조회(getDisclosureInfo) 응답의 boilerplate 키. AiPlanningService.executeDisclosureLookup()이 사용.
    public static final java.util.Set<String> DISCLOSURE_BOILERPLATE_KEYS =
            java.util.Set.of("rcept_no", "corp_cls", "corp_code", "corp_name", "stock_code");

    // items의 모든 행에서 boilerplateKeys를 제외한 나머지 필드가 전부 null/빈값/"-"이면 true —
    // DART가 행 자체는 내려줬지만 실질 데이터가 없는 경우(주로 비상장 회사)를 잡아낸다.
    public boolean isBlankOfContent(List<Map<String, Object>> items, java.util.Set<String> boilerplateKeys) {
        return items.stream().allMatch(item -> item.entrySet().stream()
                .filter(entry -> !boilerplateKeys.contains(entry.getKey()))
                .noneMatch(entry -> entry.getValue() != null
                        && !entry.getValue().toString().isBlank()
                        && !"-".equals(entry.getValue().toString())));
    }

    // list 배열을 그대로 Map으로 담아 반환한다(위 설명 참고). status가 실패거나 list가 없으면
    // 예외를 던지지 않고 빈 리스트를 반환한다 — 재무제표 쪽과 동일하게 "해당 공시가 없다"로
    // 자연스럽게 처리되도록 한다. 최신순으로 MAX_DISCLOSURE_ITEMS개까지만 잘라 반환한다.
    private List<Map<String, Object>> fetchDisclosureList(String endpoint, String corpCode, Map<String, String> extraParams) {
        List<Map<String, Object>> items = fetchAllDisclosureItems(endpoint, corpCode, extraParams);
        return items.size() > MAX_DISCLOSURE_ITEMS ? items.subList(0, MAX_DISCLOSURE_ITEMS) : items;
    }

    // fetchDisclosureList()의 줄 수 제한 없는 버전 — 배당 공시처럼 한 보고서의 여러 줄을 전부 봐야 하는 조회용.
    private List<Map<String, Object>> fetchAllDisclosureItems(String endpoint, String corpCode, Map<String, String> extraParams) {
        StringBuilder uriBuilder = new StringBuilder(apiUrl.substring(0, apiUrl.lastIndexOf('/') + 1))
                .append(endpoint)
                .append("?crtfc_key={apiKey}&corp_code={corpCode}");
        Map<String, Object> uriVariables = new HashMap<>();
        uriVariables.put("apiKey", apiKey);
        uriVariables.put("corpCode", corpCode);
        for (Map.Entry<String, String> entry : extraParams.entrySet()) {
            uriBuilder.append("&").append(entry.getKey()).append("={").append(entry.getKey()).append("}");
            uriVariables.put(entry.getKey(), entry.getValue());
        }

        GenericDartListResponse response = ExternalApiInvoker.call(() -> restClient.get()
                        .uri(uriBuilder.toString(), uriVariables)
                        .retrieve()
                        .body(GenericDartListResponse.class),
                "DART 공시 조회 실패 - endpoint: {}, corpCode: {}", endpoint, corpCode);

        if (response == null) {
            throw new CustomException(ErrorCode.EXTERNAL_API_ERROR);
        }
        if (NO_DATA_STATUS.equals(response.status())) {
            return List.of();
        }
        requireSuccessfulStatus(response.status(), response.message());
        return response.list() == null ? List.of() : response.list();
    }

    // ===== 배당 공시(종목 리서치 "배당 공시" 탭, fix/stock-detail-review) =====
    //
    // AI 상담의 getDisclosureInfo("배당사항")는 프롬프트 길이 때문에 5줄까지만 돌려줘, 15줄 안팎인 배당 공시에서 정작
    // 주당 현금배당금·배당성향·시가배당률이 잘린다(삼성전자는 액면가·순이익·EPS·배당금총액만 남았다). 그래서 리서치 탭은
    // 이 메서드로 전체 줄을 받아 배당 판단에 쓰는 줄만 남긴다. 분기·반기 보고서는 당기가 그 해 누적이라 연간 지표로 읽기
    // 어려워, 한 해 전체 값이 나오는 작년 사업보고서를 쓰고(3월 말 제출 전이면 없을 수 있어) 없으면 재작년으로 대체한다.
    private static final String ENDPOINT_DIVIDEND = "alotMatter.json";
    private static final int DIVIDEND_YEAR_OFFSET = 1;
    private static final int DIVIDEND_FALLBACK_YEAR_OFFSET = 2;
    // 배당 판단과 무관하거나(액면가) 연결 값과 중복되는(별도 재무제표 기준) 줄은 뺀다.
    private static final java.util.Set<String> DIVIDEND_EXCLUDED_LABELS =
            java.util.Set.of("주당액면가액(원)", "(별도)당기순이익(백만원)");
    // 화면에 보여줄 순서 — 배당 자체 지표를 먼저, 배당성향 계산 근거(순이익·EPS)를 뒤에 둔다. 목록에 없는 항목은 맨 뒤.
    private static final List<String> DIVIDEND_LABEL_ORDER = List.of(
            "주당 현금배당금(원)", "현금배당수익률(%)", "(연결)현금배당성향(%)", "현금배당금총액(백만원)",
            "주당 주식배당(주)", "주식배당수익률(%)", "주식배당금총액(백만원)",
            "(연결)당기순이익(백만원)", "(연결)주당순이익(원)");
    private static final String DART_VIEWER_URL = "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=";

    /** 작년(없으면 재작년) 사업보고서의 배당에 관한 사항. 공시가 없으면 indicators가 빈 응답. */
    public DartDividendResponse getDividendDisclosure(String corpCode) {
        int currentYear = LocalDate.now(KST).getYear();
        for (int offset : List.of(DIVIDEND_YEAR_OFFSET, DIVIDEND_FALLBACK_YEAR_OFFSET)) {
            int year = currentYear - offset;
            List<Map<String, Object>> rows = fetchAllDisclosureItems(ENDPOINT_DIVIDEND, corpCode, Map.of(
                    "bsns_year", String.valueOf(year),
                    "reprt_code", REPORT_CODE_BUSINESS));
            if (!rows.isEmpty() && !isBlankOfContent(rows, YEAR_REPORT_BOILERPLATE_KEYS)) {
                return toDividendResponse(corpCode, year, rows);
            }
        }
        return new DartDividendResponse(corpCode, currentYear - DIVIDEND_YEAR_OFFSET, REPORT_CODE_BUSINESS, null, null, List.of());
    }

    /** DART 공시 원문 뷰어 주소. 접수번호가 없으면 null. */
    public static String viewerUrl(String receiptNo) {
        return receiptNo == null || receiptNo.isBlank() ? null : DART_VIEWER_URL + receiptNo;
    }

    private DartDividendResponse toDividendResponse(String corpCode, int year, List<Map<String, Object>> rows) {
        List<DartDividendResponse.DividendIndicator> indicators = rows.stream()
                .map(row -> new DartDividendResponse.DividendIndicator(
                        dividendText(row.get("se")),
                        dividendText(row.get("stock_knd")),
                        dividendNumber(row.get("thstrm")),
                        dividendNumber(row.get("frmtrm")),
                        dividendNumber(row.get("lwfr"))))
                .filter(indicator -> indicator.label() != null && !DIVIDEND_EXCLUDED_LABELS.contains(indicator.label()))
                // 주식배당처럼 세 해 모두 "-"인 줄은 "해당 없음"이라 보여줄 게 없다.
                .filter(indicator -> indicator.currentValue() != null || indicator.previousValue() != null
                        || indicator.beforePreviousValue() != null)
                .sorted(Comparator.comparingInt(this::dividendLabelRank))
                .toList();
        Map<String, Object> first = rows.get(0);
        return new DartDividendResponse(corpCode, year, REPORT_CODE_BUSINESS,
                dividendText(first.get("rcept_no")), dividendText(first.get("stlm_dt")), indicators);
    }

    private int dividendLabelRank(DartDividendResponse.DividendIndicator indicator) {
        int rank = DIVIDEND_LABEL_ORDER.indexOf(indicator.label());
        return rank < 0 ? DIVIDEND_LABEL_ORDER.size() : rank;
    }

    // DART는 값이 없거나 해당 없는 칸을 "-"로 준다 — 화면에서 "미제공"으로 보이도록 null로 바꾼다(기업개황 필드에도 쓴다).
    private String dividendText(Object raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.toString().trim();
        return value.isEmpty() || "-".equals(value) ? null : value;
    }

    // "1,668"·"25.10"·"-1.5" 같은 DART 표기를 숫자로 바꾼다 — 프론트가 단위 변환·정렬을 할 수 있게. 숫자가 아니면 null.
    private Double dividendNumber(Object raw) {
        String value = dividendText(raw);
        if (value == null) {
            return null;
        }
        try {
            return Double.parseDouble(value.replace(",", ""));
        } catch (NumberFormatException e) {
            log.warn("DART 배당 값 파싱 실패 - rawValue: {}", value);
            return null;
        }
    }

    private record GenericDartListResponse(String status, String message, List<Map<String, Object>> list) {
    }

    private void requireSuccessfulStatus(String status, String message) {
        if (SUCCESS_STATUS.equals(status)) return;
        log.warn("DART API 응답 오류 - status: {}, message: {}", status, message);
        if (RATE_LIMIT_STATUS.equals(status)) {
            throw new CustomException(ErrorCode.DART_RATE_LIMIT_EXCEEDED);
        }
        throw new CustomException(ErrorCode.EXTERNAL_API_ERROR);
    }

    // 실제 데이터를 하나도 못 찾으면(공시 자체가 없는 경우) null을 반환해 호출자가 "다음 보고서
    // 종류로 넘어갈지" 판단하게 한다 — 항목이 전부 null인 빈 응답과 "아직 이 호출을 안 해봤다"를
    // 구분하기 위함이다.
    private DartFinancialResponse fetchFinancials(String corpCode, int year, String reportCode) {
        FetchedAccounts fetched = fetchAccounts(corpCode, year, reportCode);
        return fetched == null ? null : toFinancialResponse(corpCode, year, reportCode, fetched, DartAccountItem::thstrmAmount);
    }

    // 연결재무제표(CFS)를 먼저, 계정이 하나도 없으면 개별재무제표(OFS)로 조회한다. 둘 다 없으면 null.
    private FetchedAccounts fetchAccounts(String corpCode, int year, String reportCode) {
        List<DartAccountItem> items = fetchAccountItems(corpCode, year, reportCode, FS_DIV_CONSOLIDATED);
        if (!items.isEmpty()) {
            return new FetchedAccounts(FS_DIV_CONSOLIDATED, items);
        }
        items = fetchAccountItems(corpCode, year, reportCode, FS_DIV_INDIVIDUAL);
        return items.isEmpty() ? null : new FetchedAccounts(FS_DIV_INDIVIDUAL, items);
    }

    private record FetchedAccounts(String fsDiv, List<DartAccountItem> items) {

        String receiptNo() {
            return items.stream().map(DartAccountItem::rceptNo)
                    .filter(value -> value != null && !value.isBlank()).findFirst().orElse(null);
        }
    }

    // amountField로 어느 기간 값을 읽을지 고른다 — 당기(thstrm_amount), 전기(frmtrm_amount), 전전기(bfefrmtrm_amount).
    private DartFinancialResponse toFinancialResponse(String corpCode, int year, String reportCode, FetchedAccounts fetched,
                                                      Function<DartAccountItem, String> amountField) {
        List<DartAccountItem> items = fetched.items();
        List<String> balanceSheet = List.of(STATEMENT_BALANCE_SHEET);
        return new DartFinancialResponse(
                corpCode,
                year,
                findAmount(items, ACCOUNT_ID_REVENUE, INCOME_STATEMENTS, List.of(ACCOUNT_REVENUE), amountField),
                findAmount(items, ACCOUNT_ID_OPERATING_PROFIT, INCOME_STATEMENTS, List.of(ACCOUNT_OPERATING_PROFIT), amountField),
                findNetIncome(items, amountField),
                findAmount(items, ACCOUNT_ID_TOTAL_ASSETS, balanceSheet, List.of(ACCOUNT_TOTAL_ASSETS), amountField),
                findAmount(items, ACCOUNT_ID_TOTAL_LIABILITIES, balanceSheet, List.of(ACCOUNT_TOTAL_LIABILITIES), amountField),
                findAmount(items, ACCOUNT_ID_TOTAL_EQUITY, balanceSheet, List.of(ACCOUNT_TOTAL_EQUITY), amountField),
                reportCode,
                fetched.fsDiv(),
                fetched.receiptNo()
        );
    }

    // 당기순이익 합계(ifrs-full_ProfitLoss)가 없고 "지배기업 소유주지분 + 비지배지분"으로만 공시하는 회사가 있다(레인보우로보틱스
    // 2025 연결 — 2026-10-11 실응답 확인). 그때는 두 값을 더해 합계를 만든다.
    private Long findNetIncome(List<DartAccountItem> items, Function<DartAccountItem, String> amountField) {
        Long total = findAmount(items, ACCOUNT_ID_NET_INCOME, INCOME_STATEMENTS, NET_INCOME_ACCOUNT_NAMES, amountField);
        if (total != null) {
            return total;
        }
        Long owners = findAmount(items, ACCOUNT_ID_NET_INCOME_OWNERS, INCOME_STATEMENTS, List.of(), amountField);
        if (owners == null) {
            return null;
        }
        Long nonControlling = findAmount(items, ACCOUNT_ID_NET_INCOME_NON_CONTROLLING, INCOME_STATEMENTS, List.of(), amountField);
        return nonControlling == null ? owners : owners + nonControlling;
    }

    // 기본주당이익(EPS) 합계가 없으면 — 중단영업이 있는 회사는 계속영업·중단영업 EPS를 나눠 공시하고(삼성바이오로직스·카카오·
    // 한화에어로스페이스 등), 표준 계정 ID 없이 계정명으로만 공시하는 회사도 있다(LG "보통주 기본/희석주당순이익") — 2026-10-11 105종목 점검.
    private Long findEps(List<DartAccountItem> items, Function<DartAccountItem, String> amountField) {
        Long total = findAmount(items, ACCOUNT_ID_EPS, INCOME_STATEMENTS, EPS_ACCOUNT_NAMES, amountField);
        if (total != null) {
            return total;
        }
        Long continuing = findAmount(items, ACCOUNT_ID_EPS_CONTINUING, INCOME_STATEMENTS, List.of(), amountField);
        if (continuing == null) {
            return null;
        }
        Long discontinued = findAmount(items, ACCOUNT_ID_EPS_DISCONTINUED, INCOME_STATEMENTS, List.of(), amountField);
        return discontinued == null ? continuing : continuing + discontinued;
    }

    // 은행·보험·증권 등 금융업은 매출액(ifrs-full_Revenue) 대신 이자수익·보험수익·수수료수익으로 나눠 공시한다(KB금융·삼성생명 등 7곳,
    // 2026-10-11 105종목 점검). 이 항목들을 더해 매출을 만들면 회사마다 기준이 달라져, 매출 관련 값은 비우고 금융업으로 표시한다.
    private boolean isFinancialCompany(List<DartAccountItem> items) {
        boolean hasRevenue = items.stream().anyMatch(item -> ACCOUNT_ID_REVENUE.equals(item.accountId())
                && INCOME_STATEMENTS.contains(item.sjDiv()));
        return !hasRevenue && items.stream().anyMatch(item -> FINANCIAL_REVENUE_ACCOUNT_IDS.contains(item.accountId())
                && INCOME_STATEMENTS.contains(item.sjDiv()));
    }

    private DartFinancialDetailResponse toFinancialDetail(String corpCode, int year, FetchedAccounts fetched,
                                                          Function<DartAccountItem, String> amountField) {
        List<DartAccountItem> items = fetched.items();
        List<String> balanceSheet = List.of(STATEMENT_BALANCE_SHEET);
        List<String> cashFlow = List.of(STATEMENT_CASH_FLOW);
        return new DartFinancialDetailResponse(
                corpCode,
                year,
                REPORT_CODE_BUSINESS,
                fetched.fsDiv(),
                fetched.receiptNo(),
                isFinancialCompany(items),
                findAmount(items, ACCOUNT_ID_REVENUE, INCOME_STATEMENTS, List.of(ACCOUNT_REVENUE), amountField),
                findAmount(items, ACCOUNT_ID_GROSS_PROFIT, INCOME_STATEMENTS, List.of(), amountField),
                findAmount(items, ACCOUNT_ID_OPERATING_PROFIT, INCOME_STATEMENTS, List.of(ACCOUNT_OPERATING_PROFIT), amountField),
                findAmount(items, ACCOUNT_ID_PROFIT_BEFORE_TAX, INCOME_STATEMENTS, List.of(), amountField),
                findNetIncome(items, amountField),
                findAmount(items, ACCOUNT_ID_NET_INCOME_OWNERS, INCOME_STATEMENTS, List.of(), amountField),
                findEps(items, amountField),
                findAmount(items, ACCOUNT_ID_CURRENT_ASSETS, balanceSheet, List.of(), amountField),
                findAmount(items, ACCOUNT_ID_CURRENT_LIABILITIES, balanceSheet, List.of(), amountField),
                findAmount(items, ACCOUNT_ID_CASH, balanceSheet, List.of(), amountField),
                findAmount(items, ACCOUNT_ID_TOTAL_ASSETS, balanceSheet, List.of(ACCOUNT_TOTAL_ASSETS), amountField),
                findAmount(items, ACCOUNT_ID_TOTAL_LIABILITIES, balanceSheet, List.of(ACCOUNT_TOTAL_LIABILITIES), amountField),
                findAmount(items, ACCOUNT_ID_ISSUED_CAPITAL, balanceSheet, List.of(), amountField),
                findAmount(items, ACCOUNT_ID_RETAINED_EARNINGS, balanceSheet, List.of(), amountField),
                findAmount(items, ACCOUNT_ID_TOTAL_EQUITY, balanceSheet, List.of(ACCOUNT_TOTAL_EQUITY), amountField),
                findAmount(items, ACCOUNT_ID_EQUITY_OWNERS, balanceSheet, List.of(), amountField),
                findAmount(items, ACCOUNT_ID_OPERATING_CASH_FLOW, cashFlow, List.of(), amountField),
                findAmount(items, ACCOUNT_ID_INVESTING_CASH_FLOW, cashFlow, List.of(), amountField),
                findAmount(items, ACCOUNT_ID_FINANCING_CASH_FLOW, cashFlow, List.of(), amountField)
        );
    }

    /**
     * 종목 리서치 "재무제표"·"개요" 탭용 3개년 상세(fix/stock-detail-review) — request.year() 사업보고서 한 건에 당기·전기·전전기
     * 값이 함께 오므로 DART를 더 부르지 않고 세 해를 만든다(최신순). 공시가 없으면 빈 목록, 값이 하나도 없는 해(상장 전 등)는 뺀다.
     */
    public List<DartFinancialDetailResponse> getFinancialTrend(DartFinancialRequest request) {
        FetchedAccounts fetched = fetchAccounts(request.corpCode(), request.year(), REPORT_CODE_BUSINESS);
        if (fetched == null) {
            return List.of();
        }
        List<Function<DartAccountItem, String>> amountFields = List.of(
                DartAccountItem::thstrmAmount, DartAccountItem::frmtrmAmount, DartAccountItem::bfefrmtrmAmount);
        List<DartFinancialDetailResponse> years = new java.util.ArrayList<>();
        for (int i = 0; i < amountFields.size(); i++) {
            DartFinancialDetailResponse year = toFinancialDetail(request.corpCode(), request.year() - i, fetched, amountFields.get(i));
            if (year.revenue() != null || year.operatingProfit() != null || year.netIncome() != null || year.totalAssets() != null) {
                years.add(year);
            }
        }
        return years;
    }

    /**
     * 종목 리서치 "분기 실적" 탭용 — 가장 최근 분기 손익과 전년 같은 분기 손익(fix/stock-detail-review). 보고서 고르는 순서는
     * {@link #getRecentQuarterlyFinancials(String)}와 같고, 분기 보고서를 못 찾으면 작년 사업보고서의 한 해 값과 전년 값을 쓴다.
     */
    public DartEarningsResponse getRecentEarnings(String corpCode) {
        LocalDate today = LocalDate.now(KST);
        if (isAnnualReportFirst(today)) {
            FetchedAccounts annual = fetchAccounts(corpCode, today.getYear() - 1, REPORT_CODE_BUSINESS);
            if (annual != null) {
                return toEarningsResponse(corpCode, today.getYear() - 1, REPORT_CODE_BUSINESS, annual, DartAccountItem::frmtrmAmount);
            }
        }
        ReportPeriod likelyAvailable = mostRecentLikelyAvailableQuarter(today);
        int startIndex = QUARTERLY_REPORT_CODES_NEWEST_FIRST.indexOf(likelyAvailable.reportCode());
        for (int i = startIndex; i < QUARTERLY_REPORT_CODES_NEWEST_FIRST.size(); i++) {
            String reportCode = QUARTERLY_REPORT_CODES_NEWEST_FIRST.get(i);
            FetchedAccounts fetched = fetchAccounts(corpCode, likelyAvailable.year(), reportCode);
            if (fetched != null) {
                return toEarningsResponse(corpCode, likelyAvailable.year(), reportCode, fetched, DartAccountItem::frmtrmQAmount);
            }
        }
        int fallbackYear = LocalDate.now(KST).getYear() - ANNUAL_FALLBACK_YEAR_OFFSET;
        FetchedAccounts annual = fetchAccounts(corpCode, fallbackYear, REPORT_CODE_BUSINESS);
        if (annual != null) {
            return toEarningsResponse(corpCode, fallbackYear, REPORT_CODE_BUSINESS, annual, DartAccountItem::frmtrmAmount);
        }
        return new DartEarningsResponse(corpCode, fallbackYear, null, null, null, false, null, null, null, null, null, null);
    }

    private DartEarningsResponse toEarningsResponse(String corpCode, int year, String reportCode, FetchedAccounts fetched,
                                                    Function<DartAccountItem, String> previousField) {
        DartFinancialResponse current = toFinancialResponse(corpCode, year, reportCode, fetched, DartAccountItem::thstrmAmount);
        DartFinancialResponse previous = toFinancialResponse(corpCode, year - 1, reportCode, fetched, previousField);
        return new DartEarningsResponse(corpCode, year, reportCode, fetched.fsDiv(), fetched.receiptNo(),
                isFinancialCompany(fetched.items()),
                current.revenue(), current.operatingProfit(), current.netIncome(),
                previous.revenue(), previous.operatingProfit(), previous.netIncome());
    }

    // ===== 기업개황(종목 리서치 "개요" 탭, fix/stock-detail-review) =====
    private static final String ENDPOINT_COMPANY = "company.json";
    private static final String DART_COMPANY_URL = "https://dart.fss.or.kr/dsae001/selectPopup.ax?selectKey=";

    /** DART 기업개황(대표자·설립일·결산월·홈페이지·주소 등). 공시 회사가 아니거나 조회 결과가 없으면 빈 값. */
    @SuppressWarnings("unchecked")
    public Optional<DartCompanyResponse> getCompany(String corpCode) {
        String uri = apiUrl.substring(0, apiUrl.lastIndexOf('/') + 1) + ENDPOINT_COMPANY + "?crtfc_key={apiKey}&corp_code={corpCode}";
        Map<String, Object> response = ExternalApiInvoker.call(() -> restClient.get()
                        .uri(uri, apiKey, corpCode)
                        .retrieve()
                        .body(Map.class),
                "DART 기업개황 조회 실패 - corpCode: {}", corpCode);
        if (response == null) {
            throw new CustomException(ErrorCode.EXTERNAL_API_ERROR);
        }
        String status = dividendText(response.get("status"));
        if (NO_DATA_STATUS.equals(status)) {
            return Optional.empty();
        }
        requireSuccessfulStatus(status, dividendText(response.get("message")));
        return Optional.of(new DartCompanyResponse(
                corpCode,
                dividendText(response.get("corp_name")),
                dividendText(response.get("corp_name_eng")),
                dividendText(response.get("stock_name")),
                dividendText(response.get("ceo_nm")),
                dividendText(response.get("corp_cls")),
                dividendText(response.get("est_dt")),
                dividendText(response.get("acc_mt")),
                absoluteUrl(dividendText(response.get("hm_url"))),
                absoluteUrl(dividendText(response.get("ir_url"))),
                dividendText(response.get("phn_no")),
                dividendText(response.get("adres")),
                dividendText(response.get("jurir_no")),
                dividendText(response.get("bizr_no")),
                dividendText(response.get("induty_code"))));
    }

    /** DART 기업개황 웹 페이지(공시 회사 정보 팝업) 주소. */
    public static String companyPageUrl(String corpCode) {
        return corpCode == null || corpCode.isBlank() ? null : DART_COMPANY_URL + corpCode;
    }

    // DART는 홈페이지를 "www.samsung.com/sec"처럼 프로토콜 없이 준다(105종목 중 99곳). 그대로 링크로 걸면 브라우저가 우리 사이트의
    // 상대 경로로 해석해 404가 나므로 https://를 붙인다.
    private static String absoluteUrl(String url) {
        if (url == null) {
            return null;
        }
        String trimmed = url.trim();
        return trimmed.startsWith("http://") || trimmed.startsWith("https://") ? trimmed : "https://" + trimmed;
    }

    private DartFinancialResponse emptyResponse(String corpCode, int year) {
        return new DartFinancialResponse(corpCode, year, null, null, null, null, null, null, null, null, null);
    }

    private List<DartAccountItem> fetchAccountItems(String corpCode, int year, String reportCode, String fsDiv) {
        return ExternalApiInvoker.call(() -> {
            DartApiResponse response = restClient.get()
                    .uri(apiUrl + "?crtfc_key={apiKey}&corp_code={corpCode}&bsns_year={year}&reprt_code={reprtCode}&fs_div={fsDiv}",
                            apiKey, corpCode, year, reportCode, fsDiv)
                    .retrieve()
                    .body(DartApiResponse.class);

            if (response == null) {
                throw new CustomException(ErrorCode.EXTERNAL_API_ERROR);
            }
            if (NO_DATA_STATUS.equals(response.status())) {
                return List.<DartAccountItem>of();
            }
            requireSuccessfulStatus(response.status(), response.message());
            if (response.list() == null) return List.<DartAccountItem>of();

            return response.list();
        }, "DART API 호출 실패 - corpCode: {}, reportCode: {}, fsDiv: {}", corpCode, reportCode, fsDiv);
    }

    // DART는 손익 계정(영업이익/당기순이익 등)을 "영업이익"으로 표기하는 회사가 있는가 하면,
    // "영업이익(손실)"처럼 손실 가능성을 계정명에 그대로 붙여 공시하는 회사도 있다(실제로
    // SK하이닉스가 이 경우였다 — 삼성전자는 "영업이익"으로만 나와 이 차이를 못 보고 넘어갈
    // 뻔했다). 정확히 일치하는 이름이 없으면 "(손실)"이 붙은 이름도 함께 확인한다.
    private static final String LOSS_SUFFIX = "(손실)";

    // 1) DART 표준 계정 ID로 정해진 재무제표(statements 순서대로)에서 찾고, 2) 없으면(계정 ID가 없는 응답) 기존처럼
    // 후보 계정명을 순서대로 시도한다 — 당기순이익은 보고서 종류에 따라 이름 자체가 다르므로(NET_INCOME_ACCOUNT_NAMES)
    // 여러 후보를 확인한다. 계정명으로 찾을 때도 자본변동표·현금흐름표 줄은 건너뛴다(구성요소별 값이라 총액이 아니다).
    private Long findAmount(List<DartAccountItem> items, String accountId, List<String> statements,
                            List<String> accountNameCandidates, Function<DartAccountItem, String> amountField) {
        for (String statement : statements) {
            Optional<DartAccountItem> found = items.stream()
                    .filter(item -> accountId.equals(item.accountId()) && statement.equals(item.sjDiv()))
                    .findFirst();
            if (found.isPresent()) {
                return parseAmount(amountField.apply(found.get()));
            }
        }
        for (String accountName : accountNameCandidates) {
            String accountNameWithLossSuffix = accountName + LOSS_SUFFIX;
            Optional<DartAccountItem> found = items.stream()
                    .filter(item -> item.sjDiv() == null || statements.contains(item.sjDiv()))
                    .filter(item -> accountName.equals(item.accountNm()) || accountNameWithLossSuffix.equals(item.accountNm()))
                    .findFirst();
            if (found.isPresent()) {
                return parseAmount(amountField.apply(found.get()));
            }
        }
        return null;
    }

    private Long parseAmount(String rawAmount) {
        if (rawAmount == null || rawAmount.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(rawAmount.replaceAll("[^0-9-]", ""));
        } catch (NumberFormatException e) {
            log.warn("DART 금액 파싱 실패 - rawAmount: {}", rawAmount);
            return null;
        }
    }

    /**
     * 회사명(예: "삼성전자")으로 DART corp_code를 찾는다. AI 재무설계 상담의
     * get_financial_statements 도구가 사용자 질문에서 판단한 회사명을 그대로 이 메서드에 넘기면,
     * 그 결과로 getFinancials()를 호출할 수 있는 corp_code를 얻는다. 목록에 없는 회사명이거나
     * 목록 다운로드 자체가 실패하면 빈 값을 반환한다 — 호출자가 "재무제표를 찾지 못했다"로
     * 자연스럽게 처리하도록 예외를 던지지 않는다.
     */
    public Optional<String> resolveCorpCodeByName(String companyName) {
        if (companyName == null || companyName.isBlank()) {
            return Optional.empty();
        }
        String trimmed = companyName.trim();
        String exact = corpCodeCache().get(trimmed);
        if (exact != null) {
            return Optional.of(exact);
        }
        return resolveWithFallbacks(trimmed, normalizedCorpCodeCache());
    }

    /** DART의 상장 종목코드로 회사 코드를 찾는다. 우선주가 목록에 없으면 같은 발행사의 보통주 코드를 사용한다. */
    public Optional<String> resolveCorpCodeByStockCode(String stockCode) {
        if (stockCode == null || !stockCode.matches("[0-9]{6}")) return Optional.empty();
        Map<String, String> codes = corpCodeByStockCodeCache();
        String exact = codes.get(stockCode);
        if (exact != null) return Optional.of(exact);
        char last = stockCode.charAt(5);
        if (last == '5' || last == '7' || last == '8' || last == '9') {
            return Optional.ofNullable(codes.get(stockCode.substring(0, 5) + "0"));
        }
        return Optional.empty();
    }

    /**
     * 회사명(예: "삼성전자")으로 KRX 종목코드(stock_code)를 찾는다. AI 재무설계 상담의
     * get_current_price 도구가 외부 시세 데이터 제공사 실시간 시세 캐시(Redis stock:price:{stockCode})를
     * 조회할 stockCode를 얻기 위해 사용한다. corp_code와 같은 DART 고유번호 목록에서 함께
     * 파싱되므로 별도 다운로드 없이 resolveCorpCodeByName()과 캐시를 공유한다. 비상장 회사는
     * stock_code 자체가 없어 빈 값을 반환한다.
     */
    public Optional<String> resolveStockCodeByName(String companyName) {
        if (companyName == null || companyName.isBlank()) {
            return Optional.empty();
        }
        String trimmed = companyName.trim();
        String exact = stockCodeCache().get(trimmed);
        if (exact != null) {
            return Optional.of(exact);
        }
        return resolveWithFallbacks(trimmed, normalizedStockCodeCache());
    }

    public List<ListedStock> searchListedStocks(String query, int limit) {
        if (query == null || query.isBlank() || limit <= 0) {
            return List.of();
        }
        String needle = normalizeForMatch(query.trim());
        if (needle.isEmpty()) {
            return List.of();
        }
        return stockCodeCache().entrySet().stream()
                .filter(entry -> entry.getValue().startsWith(needle)
                        || normalizeForMatch(entry.getKey()).contains(needle))
                .sorted(Comparator
                        .comparingInt((Map.Entry<String, String> entry) -> searchMatchRank(entry, needle))
                        .thenComparingInt(entry -> normalizeForMatch(entry.getKey()).length())
                        .thenComparing(Map.Entry::getKey))
                .limit(Math.min(limit, 20))
                .map(entry -> new ListedStock(entry.getValue(), entry.getKey()))
                .toList();
    }

    private int searchMatchRank(Map.Entry<String, String> entry, String needle) {
        String name = normalizeForMatch(entry.getKey());
        if (entry.getValue().equals(needle) || name.equals(needle)) return 0;
        if (entry.getValue().startsWith(needle) || name.startsWith(needle)) return 1;
        return 2;
    }

    // 정확 일치가 실패했을 때 시도하는 두 단계 폴백 — (1) 대소문자·법인 접미사 무시한 정규화
    // 일치, (2) 그래도 안 되면 GROUP_NAME_ALIASES로 영문↔한글 그룹명을 서로 바꿔치기한 뒤
    // 다시 (1)을 시도한다. normalizedMap은 호출부(corp/stock 각각)가 넘겨준다.
    private Optional<String> resolveWithFallbacks(String rawName, Map<String, String> normalizedMap) {
        String normalized = normalizeForMatch(rawName);
        String hit = normalizedMap.get(normalized);
        if (hit != null) {
            return Optional.of(hit);
        }
        // 2026-08-13 버그 수정 — startsWith를 rawName(원본 대소문자)에 직접 걸어서, 소문자
        // "sk쉴더스"가 "SK"로 시작하는지 검사할 때 대소문자가 달라 매번 실패했다("sk" != "SK").
        // 대문자로 맞춘 rawUpper로 접두어 판정만 하고, 실제 치환은 정규화된 문자열
        // (normalized, 이미 전체가 대문자+공백제거)에 대해 수행해 대소문자 문제를 원천적으로
        // 없앤다.
        String rawUpper = rawName.toUpperCase(java.util.Locale.ROOT);
        for (Map.Entry<String, String> alias : GROUP_NAME_ALIASES.entrySet()) {
            String substituted = null;
            if (rawUpper.startsWith(alias.getKey())) {
                substituted = alias.getValue() + normalized.substring(alias.getKey().length());
            } else if (normalized.startsWith(alias.getValue())) {
                substituted = alias.getKey() + normalized.substring(alias.getValue().length());
            }
            if (substituted != null) {
                String aliasHit = normalizedMap.get(normalizeForMatch(substituted));
                if (aliasHit != null) {
                    return Optional.of(aliasHit);
                }
            }
        }
        return Optional.empty();
    }

    // 대소문자 무시 + "주식회사"/"(주)"/"㈜" 같은 흔한 법인 접미사·기호와 공백을 제거해서
    // "LG", "lg", "LG주식회사", "(주)LG"가 전부 같은 키로 모이게 한다.
    private static final java.util.List<String> CORP_SUFFIX_TOKENS =
            java.util.List.of("주식회사", "(주)", "㈜");

    private String normalizeForMatch(String name) {
        String result = name.toUpperCase(java.util.Locale.ROOT);
        for (String token : CORP_SUFFIX_TOKENS) {
            result = result.replace(token.toUpperCase(java.util.Locale.ROOT), "");
        }
        return result.replaceAll("\\s+", "");
    }

    // 목록 다운로드/파싱이 실패했을 때 빈 Map을 캐시에 그대로 저장해버리면, 그 실패가 일시적인
    // 네트워크 장애였더라도 다음 호출부터는 "cache != null" 빠른 경로를 타서 영구히 재시도를
    // 안 하게 된다(실제 DART 목록은 수천 건이라 "성공했는데 진짜로 비어있음"은 사실상 없다).
    // 그래서 실패 시에는 volatile 필드에 아무것도 대입하지 않고, 이번 호출에서만 빈 Map을
    // 돌려줘 다음 호출이 다시 다운로드를 시도하게 한다.
    private Map<String, String> corpCodeCache() {
        Map<String, String> cache = corpCodeByName;
        if (cache != null) {
            return cache;
        }
        ensureCorpCodeMapsLoaded();
        return corpCodeByName != null ? corpCodeByName : Map.of();
    }

    // corpCodeCache()와 완전히 동일한 캐시(같은 zip 다운로드/파싱 결과)를 stockCode 기준으로
    // 읽는 버전 — corpCodeByName과 stockCodeByName은 항상 ensureCorpCodeMapsLoaded()에서
    // 같은 시점에 함께 채워진다.
    private Map<String, String> stockCodeCache() {
        Map<String, String> cache = stockCodeByName;
        if (cache != null) {
            return cache;
        }
        ensureCorpCodeMapsLoaded();
        return stockCodeByName != null ? stockCodeByName : Map.of();
    }

    private Map<String, String> corpCodeByStockCodeCache() {
        Map<String, String> cache = corpCodeByStockCode;
        if (cache != null) return cache;
        ensureCorpCodeMapsLoaded();
        return corpCodeByStockCode != null ? corpCodeByStockCode : Map.of();
    }

    private Map<String, String> normalizedCorpCodeCache() {
        Map<String, String> cache = normalizedCorpCodeByName;
        if (cache != null) {
            return cache;
        }
        ensureCorpCodeMapsLoaded();
        return normalizedCorpCodeByName != null ? normalizedCorpCodeByName : Map.of();
    }

    private Map<String, String> normalizedStockCodeCache() {
        Map<String, String> cache = normalizedStockCodeByName;
        if (cache != null) {
            return cache;
        }
        ensureCorpCodeMapsLoaded();
        return normalizedStockCodeByName != null ? normalizedStockCodeByName : Map.of();
    }

    private synchronized void ensureCorpCodeMapsLoaded() {
        if (corpCodeByName != null) {
            return;
        }
        CorpCodeMaps loaded = loadCorpCodeMaps();
        if (loaded == null) {
            return;
        }
        stockCodeByName = loaded.stockCodeByName();
        corpCodeByStockCode = loaded.corpCodeByStockCode();
        // putIfAbsent로 채운다 — 원본 맵과 동일하게, 정규화 후 이름이 겹치는 경우 먼저 들어온
        // (상장사 우선 순서로 채워진) 항목이 이긴다.
        Map<String, String> normalizedCorp = new HashMap<>();
        loaded.corpCodeByName().forEach((name, code) -> normalizedCorp.putIfAbsent(normalizeForMatch(name), code));
        Map<String, String> normalizedStock = new HashMap<>();
        stockCodeByName.forEach((name, code) -> normalizedStock.putIfAbsent(normalizeForMatch(name), code));
        normalizedCorpCodeByName = normalizedCorp;
        normalizedStockCodeByName = normalizedStock;
        corpCodeByName = loaded.corpCodeByName();
    }

    private record CorpCodeMaps(Map<String, String> corpCodeByName, Map<String, String> stockCodeByName,
                                Map<String, String> corpCodeByStockCode) {
    }

    // corpCode.xml을 내려받아 (회사명→corp_code)와 (회사명→stock_code) 두 맵으로 함께 파싱한다.
    // 응답은 zip 압축된 바이너리이므로 메모리에서 바로 풀어 그 안의 CORPCODE.xml만 XML로
    // 파싱한다. 비상장 회사도 재무제표 없이 공시(합병·최대주주 등)만 조회하려는 질문이 있을 수
    // 있어 corp_code 캐시에는 포함하되, 상장사와 이름이 겹치면 상장사를 우선한다 — DART 전체
    // 등록 회사는 비상장 포함 수만 건이라 이름이 겹치는 군소 비상장사가 존재할 수 있는 반면,
    // 모의투자 서비스 특성상 사용자가 말하는 회사명은 거의 항상 상장사를 가리키기 때문이다.
    // 다운로드/압축해제 자체가 실패하면(일시적 장애일 수 있음) null을 반환해 호출자가 이번만
    // 빈 값을 쓰고 캐시에는 남기지 않도록 한다 — 성공적으로 파싱했는데 항목이 0개인 경우
    // (Map.of())와 구분한다. resolveCorpCodeByName()/resolveStockCodeByName()의 "예외를
    // 던지지 않는다"는 계약을 지키려면 ExternalApiInvoker.call()이 던지는 CustomException
    // (네트워크 실패 등)도 여기서 흡수해야 한다 — 그냥 던지게 두면 호출자가 try/catch 없이
    // 부른 경우 그대로 터진다.
    private CorpCodeMaps loadCorpCodeMaps() {
        byte[] zipBytes;
        try {
            zipBytes = ExternalApiInvoker.call(() -> restClient.get()
                    .uri(corpCodeUrl + "?crtfc_key={apiKey}", apiKey)
                    .retrieve()
                    .body(byte[].class),
                    "DART 고유번호 목록 다운로드 실패");
        } catch (CustomException e) {
            return null;
        }

        if (zipBytes == null) {
            return null;
        }

        Map<String, String> corpCodes = new HashMap<>();
        Map<String, String> stockCodes = new HashMap<>();
        Map<String, String> corpCodesByStockCode = new HashMap<>();
        try (ZipInputStream zipStream = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zipStream.getNextEntry()) != null) {
                if (!CORP_CODE_ENTRY_NAME.equals(entry.getName())) {
                    continue;
                }
                // DocumentBuilder.parse(InputStream)는 다 읽고 나면 그 스트림을 내부적으로
                // 닫아버린다 — zipStream을 그대로 넘기면 다음 while 조건의 getNextEntry()가
                // "닫힌 스트림"에 대고 호출돼 IOException을 던진다(단위테스트로 발견한 실제
                // 버그). 그래서 이 항목의 내용만 별도 바이트 배열로 통째로 읽어서, zipStream과
                // 무관한 새 스트림으로 파싱한다 — zipStream은 다음 반복을 위해 열린 채로 둔다.
                byte[] entryBytes = zipStream.readAllBytes();
                parseCorpCodeXml(entryBytes, corpCodes, stockCodes, corpCodesByStockCode);
            }
        } catch (IOException e) {
            log.error("DART 고유번호 목록 압축 해제 실패", e);
            return null;
        }
        return new CorpCodeMaps(corpCodes, stockCodes, corpCodesByStockCode);
    }

    private void parseCorpCodeXml(byte[] xmlBytes, Map<String, String> corpCodes, Map<String, String> stockCodes,
                                  Map<String, String> corpCodesByStockCode) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // XXE(외부 엔티티 주입) 방지 — DART 응답이라도 원칙적으로 신뢰하지 않고 막아둔다.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(new ByteArrayInputStream(xmlBytes));

            NodeList items = document.getElementsByTagName("list");
            // 상장사를 먼저 채워 넣고, 비상장사는 이름이 아직 캐시에 없을 때만 추가한다 —
            // 이렇게 하면 이름이 겹치는 경우 항상 상장사가 우선권을 가진다.
            putCorpCodesByListedStatus(items, corpCodes, stockCodes, corpCodesByStockCode, true);
            putCorpCodesByListedStatus(items, corpCodes, stockCodes, corpCodesByStockCode, false);
        } catch (IOException | ParserConfigurationException | SAXException e) {
            log.error("DART 고유번호 목록 XML 파싱 실패", e);
        }
    }

    private void putCorpCodesByListedStatus(NodeList items, Map<String, String> corpCodes,
                                             Map<String, String> stockCodes, Map<String, String> corpCodesByStockCode,
                                             boolean onlyListed) {
        for (int i = 0; i < items.getLength(); i++) {
            Element item = (Element) items.item(i);
            String corpCode = textOf(item, "corp_code");
            String corpName = textOf(item, "corp_name");
            String stockCode = textOf(item, "stock_code");
            boolean isListed = stockCode != null && !stockCode.isBlank();
            if (corpCode == null || corpName == null || isListed != onlyListed) {
                continue;
            }
            corpCodes.putIfAbsent(corpName, corpCode);
            if (isListed) {
                stockCodes.putIfAbsent(corpName, stockCode);
                corpCodesByStockCode.putIfAbsent(stockCode, corpCode);
            }
        }
    }

    private String textOf(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        return nodes.getLength() > 0 ? nodes.item(0).getTextContent() : null;
    }

    // DART fnlttSinglAcntAll API 원본 응답 구조
    private record DartApiResponse(String status, String message, List<DartAccountItem> list) {
    }

    private record DartAccountItem(
            @JsonProperty("account_nm") String accountNm,
            @JsonProperty("thstrm_amount") String thstrmAmount,
            @JsonProperty("account_id") String accountId,
            @JsonProperty("sj_div") String sjDiv,
            @JsonProperty("rcept_no") String rceptNo,
            @JsonProperty("frmtrm_amount") String frmtrmAmount,
            @JsonProperty("bfefrmtrm_amount") String bfefrmtrmAmount,
            @JsonProperty("frmtrm_q_amount") String frmtrmQAmount
    ) {
    }
}
