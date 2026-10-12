package com.teamfp.aistock.infra.dart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.teamfp.aistock.infra.dart.dto.DartCompanyResponse;
import com.teamfp.aistock.infra.dart.dto.DartDividendResponse;
import com.teamfp.aistock.infra.dart.dto.DartEarningsResponse;
import com.teamfp.aistock.infra.dart.dto.DartFinancialDetailResponse;
import com.teamfp.aistock.infra.dart.dto.DartFinancialRequest;
import com.teamfp.aistock.infra.dart.dto.DartFinancialResponse;
import com.teamfp.aistock.infra.dart.dto.ListedStock;
import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;

/**
 * DartApiClient 단위 테스트. RestClient.Builder를 MockRestServiceServer에 바인딩해 실제
 * HTTP 요청 없이 DART 응답을 흉내낸다(다른 도메인 서비스 테스트가 Mockito로 하는 것과 달리,
 * DartApiClient는 생성자 안에서 RestClient.build()를 직접 호출해 필드로 감춰버리므로
 * RestClient 자체를 Mockito로 목킹할 수 없다 — MockRestServiceServer가 이 프로젝트에서 쓰는
 * 표준적인 대안이다).
 */
class DartApiClientTest {

    private static final String API_URL = "http://test-dart/fnlttSinglAcntAll.json";
    private static final String CORP_CODE_URL = "http://test-dart/corpCode.xml";
    private static final String API_KEY = "test-api-key";
    private static final String CORP_CODE = "00126380";

    private MockRestServiceServer mockServer;
    private DartApiClient dartApiClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        // fetchFinancialIndicatorCategories()가 두 카테고리(M210000/M220000)를 가상 스레드로
        // 동시에 조회해 실제 도착 순서가 매번 달라질 수 있어, 요청 순서를 강제하지 않는다.
        mockServer = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();

        dartApiClient = new DartApiClient(builder);
        ReflectionTestUtils.setField(dartApiClient, "apiKey", API_KEY);
        ReflectionTestUtils.setField(dartApiClient, "apiUrl", API_URL);
        ReflectionTestUtils.setField(dartApiClient, "corpCodeUrl", CORP_CODE_URL);
    }

    @Nested
    @DisplayName("연간 재무제표 조회 (getFinancials)")
    class GetFinancials {

        @Test
        @DisplayName("연결재무제표(CFS)에서 정상적으로 6개 계정을 모두 찾아 반환한다")
        void success_parsesAllAccountsFromConsolidated() {
            mockServer.expect(requestTo(Matchers.startsWith(API_URL)))
                    .andExpect(queryParam("corp_code", CORP_CODE))
                    .andExpect(queryParam("bsns_year", "2025"))
                    .andExpect(queryParam("reprt_code", "11011"))
                    .andExpect(queryParam("fs_div", "CFS"))
                    .andRespond(withSuccess(fullAccountsJson(), MediaType.APPLICATION_JSON));

            DartFinancialResponse response = dartApiClient.getFinancials(new DartFinancialRequest(CORP_CODE, 2025));

            assertThat(response.revenue()).isEqualTo(333605938000000L);
            assertThat(response.operatingProfit()).isEqualTo(43601051000000L);
            assertThat(response.netIncome()).isEqualTo(45206805000000L);
            assertThat(response.totalAssets()).isEqualTo(566942110000000L);
            assertThat(response.totalLiabilities()).isEqualTo(130621773000000L);
            assertThat(response.totalEquity()).isEqualTo(436320337000000L);
            mockServer.verify();
        }

        @Test
        @DisplayName("영업이익 계정명이 '영업이익(손실)'으로 와도 정상적으로 찾는다")
        void success_matchesLossSuffixAccountName() {
            String json = """
                    {"status":"000","message":"정상","list":[
                      {"account_nm":"영업이익(손실)","thstrm_amount":"37610283000000"}
                    ]}""";
            mockServer.expect(requestTo(Matchers.startsWith(API_URL)))
                    .andExpect(queryParam("fs_div", "CFS"))
                    .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

            DartFinancialResponse response = dartApiClient.getFinancials(new DartFinancialRequest(CORP_CODE, 2025));

            assertThat(response.operatingProfit()).isEqualTo(37610283000000L);
        }

        @ParameterizedTest(name = "당기순이익 계정명이 ''{0}''이어도 찾는다")
        @DisplayName("보고서 종류별로 다른 당기순이익 계정명(당기순이익/반기순이익/분기순이익)을 모두 인식한다")
        @CsvSource({"당기순이익", "반기순이익", "분기순이익"})
        void success_matchesNetIncomeAccountNameVariants(String accountName) {
            String json = """
                    {"status":"000","message":"정상","list":[
                      {"account_nm":"%s","thstrm_amount":"40345909000000"}
                    ]}""".formatted(accountName);
            mockServer.expect(requestTo(Matchers.startsWith(API_URL)))
                    .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

            DartFinancialResponse response = dartApiClient.getFinancials(new DartFinancialRequest(CORP_CODE, 2025));

            assertThat(response.netIncome()).isEqualTo(40345909000000L);
        }

        @Test
        @DisplayName("연결재무제표(CFS)에 데이터가 없으면 개별재무제표(OFS)로 재조회한다")
        void fallsBackToIndividual_whenConsolidatedIsEmpty() {
            mockServer.expect(requestTo(Matchers.startsWith(API_URL)))
                    .andExpect(queryParam("fs_div", "CFS"))
                    .andRespond(withSuccess("""
                            {"status":"013","message":"조회된 데이타가 없습니다."}""", MediaType.APPLICATION_JSON));
            mockServer.expect(requestTo(Matchers.startsWith(API_URL)))
                    .andExpect(queryParam("fs_div", "OFS"))
                    .andRespond(withSuccess(fullAccountsJson(), MediaType.APPLICATION_JSON));

            DartFinancialResponse response = dartApiClient.getFinancials(new DartFinancialRequest(CORP_CODE, 2025));

            assertThat(response.revenue()).isEqualTo(333605938000000L);
            mockServer.verify();
        }

        @Test
        @DisplayName("CFS/OFS 둘 다 데이터가 없으면 예외 없이 모든 필드가 null인 응답을 반환한다")
        void returnsAllNullFields_whenBothConsolidatedAndIndividualAreEmpty() {
            String emptyJson = """
                    {"status":"013","message":"조회된 데이타가 없습니다."}""";
            mockServer.expect(requestTo(Matchers.startsWith(API_URL)))
                    .andExpect(queryParam("fs_div", "CFS"))
                    .andRespond(withSuccess(emptyJson, MediaType.APPLICATION_JSON));
            mockServer.expect(requestTo(Matchers.startsWith(API_URL)))
                    .andExpect(queryParam("fs_div", "OFS"))
                    .andRespond(withSuccess(emptyJson, MediaType.APPLICATION_JSON));

            DartFinancialResponse response = dartApiClient.getFinancials(new DartFinancialRequest(CORP_CODE, 2025));

            assertThat(response.revenue()).isNull();
            assertThat(response.operatingProfit()).isNull();
            assertThat(response.netIncome()).isNull();
            assertThat(response.bizYear()).isEqualTo(2025);
            assertThat(response.corpCode()).isEqualTo(CORP_CODE);
        }
    }

    @Nested
    @DisplayName("DART 표준 계정 ID로 계정 찾기 (fix/stock-detail-review)")
    class FindByAccountId {

        @Test
        @DisplayName("계정명이 회사마다 달라도(매출/반기손이익(손실)) 계정 ID로 찾고, 자본변동표 줄은 무시한다")
        void success_matchesAccountIdInsteadOfName() {
            // 에코프로비엠 2026 반기보고서 실응답 축약 — 포괄손익계산서(CIS) 하나로 공시하고 계정명이 표준과 다르다.
            String json = """
                    {"status":"000","message":"정상","list":[
                      {"rcept_no":"20260814002535","sj_div":"BS","account_id":"ifrs-full_Assets","account_nm":"자산총계","thstrm_amount":"5431383326642"},
                      {"rcept_no":"20260814002535","sj_div":"BS","account_id":"ifrs-full_Liabilities","account_nm":"부채총계","thstrm_amount":"3334884102497"},
                      {"rcept_no":"20260814002535","sj_div":"BS","account_id":"ifrs-full_Equity","account_nm":"자본총계","thstrm_amount":"2096499224145"},
                      {"rcept_no":"20260814002535","sj_div":"SCE","account_id":"ifrs-full_ProfitLoss","account_nm":"반기손이익(손실)","thstrm_amount":"999"},
                      {"rcept_no":"20260814002535","sj_div":"CIS","account_id":"ifrs-full_Revenue","account_nm":"매출","thstrm_amount":"576711961931"},
                      {"rcept_no":"20260814002535","sj_div":"CIS","account_id":"dart_OperatingIncomeLoss","account_nm":"영업이익","thstrm_amount":"18011340475"},
                      {"rcept_no":"20260814002535","sj_div":"CIS","account_id":"ifrs-full_ProfitLoss","account_nm":"반기손이익(손실)","thstrm_amount":"-454805239"}
                    ]}""";
            mockServer.expect(requestTo(Matchers.startsWith(API_URL)))
                    .andExpect(queryParam("fs_div", "CFS"))
                    .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

            DartFinancialResponse response = dartApiClient.getFinancials(new DartFinancialRequest(CORP_CODE, 2025));

            assertThat(response.revenue()).isEqualTo(576711961931L);
            assertThat(response.operatingProfit()).isEqualTo(18011340475L);
            assertThat(response.netIncome()).isEqualTo(-454805239L);
            assertThat(response.totalAssets()).isEqualTo(5431383326642L);
            assertThat(response.totalLiabilities()).isEqualTo(3334884102497L);
            assertThat(response.totalEquity()).isEqualTo(2096499224145L);
            assertThat(response.reportCode()).isEqualTo("11011");
            assertThat(response.fsDiv()).isEqualTo("CFS");
            assertThat(response.receiptNo()).isEqualTo("20260814002535");
        }

        @Test
        @DisplayName("손익계산서(IS)가 있으면 포괄손익계산서(CIS)보다 먼저 쓴다")
        void success_prefersIncomeStatementOverComprehensive() {
            String json = """
                    {"status":"000","message":"정상","list":[
                      {"sj_div":"CIS","account_id":"ifrs-full_ProfitLoss","account_nm":"반기순이익","thstrm_amount":"1"},
                      {"sj_div":"IS","account_id":"ifrs-full_ProfitLoss","account_nm":"반기순이익","thstrm_amount":"71624461000000"}
                    ]}""";
            mockServer.expect(requestTo(Matchers.startsWith(API_URL)))
                    .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

            DartFinancialResponse response = dartApiClient.getFinancials(new DartFinancialRequest(CORP_CODE, 2025));

            assertThat(response.netIncome()).isEqualTo(71624461000000L);
        }

        @Test
        @DisplayName("연결재무제표가 없어 개별로 조회했으면 fsDiv가 OFS다")
        void fsDivIsIndividual_whenFallingBackToOfs() {
            mockServer.expect(requestTo(Matchers.startsWith(API_URL)))
                    .andExpect(queryParam("fs_div", "CFS"))
                    .andRespond(withSuccess("""
                            {"status":"013","message":"조회된 데이타가 없습니다."}""", MediaType.APPLICATION_JSON));
            mockServer.expect(requestTo(Matchers.startsWith(API_URL)))
                    .andExpect(queryParam("fs_div", "OFS"))
                    .andRespond(withSuccess(fullAccountsJson(), MediaType.APPLICATION_JSON));

            DartFinancialResponse response = dartApiClient.getFinancials(new DartFinancialRequest(CORP_CODE, 2025));

            assertThat(response.fsDiv()).isEqualTo("OFS");
        }
    }

    @Nested
    @DisplayName("재무제표 3개년·분기 실적·기업개황 (fix/stock-detail-review)")
    class ResearchTabs {

        @Test
        @DisplayName("사업보고서 한 건의 당기·전기·전전기 값으로 3개년을 만든다(최신순)")
        void financialTrend_buildsThreeYearsFromOneReport() {
            // 삼성전자 2025 사업보고서 실응답 축약.
            String json = """
                    {"status":"000","message":"정상","list":[
                      {"rcept_no":"20260310002820","sj_div":"IS","account_id":"ifrs-full_Revenue","account_nm":"매출액",
                       "thstrm_amount":"333605938000000","frmtrm_amount":"300870903000000","bfefrmtrm_amount":"258935494000000"},
                      {"rcept_no":"20260310002820","sj_div":"BS","account_id":"ifrs-full_Liabilities","account_nm":"부채총계",
                       "thstrm_amount":"130621773000000","frmtrm_amount":"112339878000000","bfefrmtrm_amount":"92228115000000"}
                    ]}""";
            mockServer.expect(requestTo(Matchers.startsWith(API_URL)))
                    .andExpect(queryParam("bsns_year", "2025"))
                    .andExpect(queryParam("reprt_code", "11011"))
                    .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

            List<DartFinancialDetailResponse> years = dartApiClient.getFinancialTrend(new DartFinancialRequest(CORP_CODE, 2025));

            assertThat(years).extracting(DartFinancialDetailResponse::bizYear).containsExactly(2025, 2024, 2023);
            assertThat(years).extracting(DartFinancialDetailResponse::revenue)
                    .containsExactly(333605938000000L, 300870903000000L, 258935494000000L);
            assertThat(years).extracting(DartFinancialDetailResponse::totalLiabilities)
                    .containsExactly(130621773000000L, 112339878000000L, 92228115000000L);
            assertThat(years).extracting(DartFinancialDetailResponse::receiptNo).containsOnly("20260310002820");
            assertThat(years).extracting(DartFinancialDetailResponse::isFinancialCompany).containsOnly(false);
        }

        @Test
        @DisplayName("재무 상세는 손익·재무상태·현금흐름 계정을 각 재무제표에서 찾는다")
        void financialTrend_readsDetailAccounts() {
            String json = """
                    {"status":"000","message":"정상","list":[
                      {"sj_div":"IS","account_id":"ifrs-full_Revenue","account_nm":"매출액","thstrm_amount":"333000"},
                      {"sj_div":"IS","account_id":"ifrs-full_GrossProfit","account_nm":"매출총이익","thstrm_amount":"129000"},
                      {"sj_div":"IS","account_id":"ifrs-full_BasicEarningsLossPerShare","account_nm":"기본주당이익","thstrm_amount":"6605"},
                      {"sj_div":"BS","account_id":"ifrs-full_CurrentAssets","account_nm":"유동자산","thstrm_amount":"247000"},
                      {"sj_div":"BS","account_id":"ifrs-full_EquityAttributableToOwnersOfParent","account_nm":"지배기업 소유주지분","thstrm_amount":"420000"},
                      {"sj_div":"SCE","account_id":"ifrs-full_EquityAttributableToOwnersOfParent","account_nm":"지배기업 소유주지분","thstrm_amount":"1"},
                      {"sj_div":"CF","account_id":"ifrs-full_CashFlowsFromUsedInOperatingActivities","account_nm":"영업활동현금흐름","thstrm_amount":"-85000"}
                    ]}""";
            mockServer.expect(requestTo(Matchers.startsWith(API_URL)))
                    .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

            DartFinancialDetailResponse latest = dartApiClient.getFinancialTrend(new DartFinancialRequest(CORP_CODE, 2025)).get(0);

            assertThat(latest.grossProfit()).isEqualTo(129000L);
            assertThat(latest.eps()).isEqualTo(6605L);
            assertThat(latest.currentAssets()).isEqualTo(247000L);
            assertThat(latest.equityOwners()).isEqualTo(420000L);
            assertThat(latest.operatingCashFlow()).isEqualTo(-85000L);
        }

        @Test
        @DisplayName("EPS 합계가 없으면 계속영업 + 중단영업 EPS를 더하고, 표준 계정 ID가 없으면 계정명으로 찾는다")
        void financialTrend_epsFallbacks() {
            // 카카오 2025 연결 실응답 축약 — 계속영업 1,138원 + 중단영업 -20원.
            String continuingJson = """
                    {"status":"000","message":"정상","list":[
                      {"sj_div":"CIS","account_id":"ifrs-full_Revenue","account_nm":"영업수익","thstrm_amount":"100"},
                      {"sj_div":"CIS","account_id":"ifrs-full_BasicEarningsLossPerShareFromContinuingOperations","account_nm":"계속영업 기본주당순이익","thstrm_amount":"1138"},
                      {"sj_div":"CIS","account_id":"ifrs-full_BasicEarningsLossPerShareFromDiscontinuedOperations","account_nm":"중단영업 기본주당순이익","thstrm_amount":"-20"}
                    ]}""";
            // LG 2025 연결 실응답 축약 — 표준 계정 ID 없이 계정명만 있다.
            String nameOnlyJson = """
                    {"status":"000","message":"정상","list":[
                      {"sj_div":"IS","account_id":"ifrs-full_Revenue","account_nm":"매출액","thstrm_amount":"100"},
                      {"sj_div":"IS","account_id":"-표준계정코드 미사용-","account_nm":"구형우선주 기본/희석주당순이익","thstrm_amount":"4830"},
                      {"sj_div":"IS","account_id":"-표준계정코드 미사용-","account_nm":"보통주 기본/희석주당순이익","thstrm_amount":"4780"}
                    ]}""";
            mockServer.expect(requestTo(Matchers.startsWith(API_URL))).andExpect(queryParam("corp_code", "00000001"))
                    .andRespond(withSuccess(continuingJson, MediaType.APPLICATION_JSON));
            mockServer.expect(requestTo(Matchers.startsWith(API_URL))).andExpect(queryParam("corp_code", "00000002"))
                    .andRespond(withSuccess(nameOnlyJson, MediaType.APPLICATION_JSON));

            assertThat(dartApiClient.getFinancialTrend(new DartFinancialRequest("00000001", 2025)).get(0).eps()).isEqualTo(1118L);
            assertThat(dartApiClient.getFinancialTrend(new DartFinancialRequest("00000002", 2025)).get(0).eps()).isEqualTo(4780L);
        }

        @Test
        @DisplayName("매출액 없이 이자수익·보험수익으로 공시하면 금융업으로 판별한다")
        void financialTrend_detectsFinancialCompany() {
            // KB금융 2025 사업보고서 실응답 축약 — ifrs-full_Revenue가 없다.
            String json = """
                    {"status":"000","message":"정상","list":[
                      {"sj_div":"CIS","account_id":"ifrs-full_RevenueFromInterest","account_nm":"이자수익","thstrm_amount":"29156100000000"},
                      {"sj_div":"CIS","account_id":"dart_OperatingIncomeLoss","account_nm":"영업이익","thstrm_amount":"8517711000000"},
                      {"sj_div":"CIS","account_id":"ifrs-full_ProfitLoss","account_nm":"당기순이익","thstrm_amount":"5840715000000"}
                    ]}""";
            mockServer.expect(requestTo(Matchers.startsWith(API_URL)))
                    .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

            DartFinancialDetailResponse latest = dartApiClient.getFinancialTrend(new DartFinancialRequest(CORP_CODE, 2025)).get(0);

            assertThat(latest.isFinancialCompany()).isTrue();
            assertThat(latest.revenue()).isNull();
            assertThat(latest.operatingProfit()).isEqualTo(8517711000000L);
        }

        @Test
        @DisplayName("당기순이익 합계가 없으면 지배기업 소유주지분 + 비지배지분으로 만든다")
        void financialTrend_netIncomeFromOwnersAndNonControlling() {
            // 레인보우로보틱스 2025 연결 실응답 축약 — ifrs-full_ProfitLoss가 없다.
            String json = """
                    {"status":"000","message":"정상","list":[
                      {"sj_div":"CIS","account_id":"ifrs-full_Revenue","account_nm":"매출액","thstrm_amount":"100"},
                      {"sj_div":"CIS","account_id":"ifrs-full_ProfitLossAttributableToOwnersOfParent","account_nm":"지배기업 소유주지분","thstrm_amount":"1421503230"},
                      {"sj_div":"CIS","account_id":"ifrs-full_ProfitLossAttributableToNoncontrollingInterests","account_nm":"비지배지분","thstrm_amount":"5"}
                    ]}""";
            mockServer.expect(requestTo(Matchers.startsWith(API_URL)))
                    .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

            DartFinancialDetailResponse latest = dartApiClient.getFinancialTrend(new DartFinancialRequest(CORP_CODE, 2025)).get(0);

            assertThat(latest.netIncome()).isEqualTo(1421503235L);
            assertThat(latest.netIncomeOwners()).isEqualTo(1421503230L);
        }

        @Test
        @DisplayName("사업보고서가 없으면 빈 목록을 돌려준다")
        void financialTrend_emptyWhenNoReport() {
            String emptyJson = """
                    {"status":"013","message":"조회된 데이타가 없습니다."}""";
            mockServer.expect(org.springframework.test.web.client.ExpectedCount.twice(), requestTo(Matchers.startsWith(API_URL)))
                    .andRespond(withSuccess(emptyJson, MediaType.APPLICATION_JSON));

            assertThat(dartApiClient.getFinancialTrend(new DartFinancialRequest(CORP_CODE, 2025))).isEmpty();
        }

        @Test
        @DisplayName("분기 실적은 그 분기 3개월 값과 전년 같은 분기(frmtrm_q_amount)를 함께 돌려준다")
        void recentEarnings_includesSameQuarterLastYear() {
            DartApiClient.ReportPeriod expected = dartApiClient.mostRecentLikelyAvailableQuarter(LocalDate.now());
            // 삼성전자 2026 반기보고서 실응답 축약.
            String json = """
                    {"status":"000","message":"정상","list":[
                      {"rcept_no":"20260814003699","sj_div":"IS","account_id":"ifrs-full_Revenue","account_nm":"매출액",
                       "thstrm_amount":"171499470000000","thstrm_add_amount":"305372914000000","frmtrm_q_amount":"74566317000000"},
                      {"rcept_no":"20260814003699","sj_div":"IS","account_id":"ifrs-full_ProfitLoss","account_nm":"반기순이익",
                       "thstrm_amount":"71624461000000","frmtrm_q_amount":"5116435000000"}
                    ]}""";
            mockServer.expect(requestTo(Matchers.startsWith(API_URL)))
                    .andExpect(queryParam("reprt_code", expected.reportCode()))
                    .andExpect(queryParam("fs_div", "CFS"))
                    .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

            DartEarningsResponse earnings = dartApiClient.getRecentEarnings(CORP_CODE);

            assertThat(earnings.reportCode()).isEqualTo(expected.reportCode());
            assertThat(earnings.bizYear()).isEqualTo(expected.year());
            assertThat(earnings.revenue()).isEqualTo(171499470000000L);
            assertThat(earnings.previousRevenue()).isEqualTo(74566317000000L);
            assertThat(earnings.netIncome()).isEqualTo(71624461000000L);
            assertThat(earnings.previousNetIncome()).isEqualTo(5116435000000L);
            assertThat(earnings.receiptNo()).isEqualTo("20260814003699");
            mockServer.verify();
        }

        @Test
        @DisplayName("기업개황은 company.json에서 대표자·설립일·결산월 등을 읽는다")
        void company_readsCompanyJson() {
            mockServer.expect(requestTo(Matchers.startsWith("http://test-dart/company.json")))
                    .andExpect(queryParam("corp_code", CORP_CODE))
                    .andRespond(withSuccess("""
                            {"status":"000","message":"정상","corp_code":"00126380","corp_name":"삼성전자(주)","stock_name":"삼성전자",
                             "ceo_nm":"전영현, 노태문","corp_cls":"Y","est_dt":"19690113","acc_mt":"12","hm_url":"www.samsung.com/sec",
                             "ir_url":"","adres":"경기도 수원시 영통구  삼성로 129 (매탄동)","corp_name_eng":"SAMSUNG ELECTRONICS CO,.LTD",
                             "phn_no":"02-2255-0114","jurir_no":"1301110006246","bizr_no":"1248100998","induty_code":"264"}""", MediaType.APPLICATION_JSON));

            DartCompanyResponse company = dartApiClient.getCompany(CORP_CODE).orElseThrow();

            assertThat(company.corpName()).isEqualTo("삼성전자(주)");
            assertThat(company.ceoName()).isEqualTo("전영현, 노태문");
            assertThat(company.establishedDate()).isEqualTo("19690113");
            assertThat(company.settlementMonth()).isEqualTo("12");
            // DART는 프로토콜 없이 주므로 https://를 붙인다(그대로 링크로 걸면 404).
            assertThat(company.homepageUrl()).isEqualTo("https://www.samsung.com/sec");
            assertThat(company.irUrl()).isNull();
            assertThat(company.corpNameEng()).isEqualTo("SAMSUNG ELECTRONICS CO,.LTD");
            assertThat(company.phoneNumber()).isEqualTo("02-2255-0114");
            assertThat(DartApiClient.companyPageUrl(CORP_CODE))
                    .isEqualTo("https://dart.fss.or.kr/dsae001/selectPopup.ax?selectKey=00126380");
        }

        @Test
        @DisplayName("기업개황이 없으면 빈 값을 돌려준다")
        void company_emptyWhenNoData() {
            mockServer.expect(requestTo(Matchers.startsWith("http://test-dart/company.json")))
                    .andRespond(withSuccess("""
                            {"status":"013","message":"조회된 데이타가 없습니다."}""", MediaType.APPLICATION_JSON));

            assertThat(dartApiClient.getCompany(CORP_CODE)).isEmpty();
        }
    }

    @Nested
    @DisplayName("배당 공시 조회 (getDividendDisclosure)")
    class GetDividendDisclosure {

        private static final String DIVIDEND_URL = "http://test-dart/alotMatter.json";

        // 삼성전자 2025 사업보고서 배당에 관한 사항 실응답(15줄) 중 일부.
        private String dividendJson() {
            return """
                    {"status":"000","message":"정상","list":[
                      {"rcept_no":"20260310002820","se":"주당액면가액(원)","stock_knd":"-","thstrm":"100","frmtrm":"100","lwfr":"100","stlm_dt":"2025-12-31"},
                      {"rcept_no":"20260310002820","se":"(연결)당기순이익(백만원)","stock_knd":"-","thstrm":"44,260,956","frmtrm":"33,621,363","lwfr":"14,473,401","stlm_dt":"2025-12-31"},
                      {"rcept_no":"20260310002820","se":"(별도)당기순이익(백만원)","stock_knd":"-","thstrm":"33,686,601","frmtrm":"23,582,565","lwfr":"25,397,099","stlm_dt":"2025-12-31"},
                      {"rcept_no":"20260310002820","se":"현금배당금총액(백만원)","stock_knd":"-","thstrm":"11,107,906","frmtrm":"9,810,767","lwfr":"9,809,438","stlm_dt":"2025-12-31"},
                      {"rcept_no":"20260310002820","se":"주식배당금총액(백만원)","stock_knd":"-","thstrm":"-","frmtrm":"-","lwfr":"-","stlm_dt":"2025-12-31"},
                      {"rcept_no":"20260310002820","se":"(연결)현금배당성향(%)","stock_knd":"-","thstrm":"25.10","frmtrm":"29.20","lwfr":"67.80","stlm_dt":"2025-12-31"},
                      {"rcept_no":"20260310002820","se":"현금배당수익률(%)","stock_knd":"보통주","thstrm":"1.50","frmtrm":"2.70","lwfr":"1.90","stlm_dt":"2025-12-31"},
                      {"rcept_no":"20260310002820","se":"주당 현금배당금(원)","stock_knd":"보통주","thstrm":"1,668","frmtrm":"1,446","lwfr":"1,444","stlm_dt":"2025-12-31"}
                    ]}""";
        }

        @Test
        @DisplayName("줄 수 제한 없이 받아 배당 지표만 남기고, 주당 현금배당금부터 정렬한다")
        void success_keepsDividendIndicatorsInOrder() {
            int lastYear = LocalDate.now().getYear() - 1;
            mockServer.expect(requestTo(Matchers.startsWith(DIVIDEND_URL)))
                    .andExpect(queryParam("bsns_year", String.valueOf(lastYear)))
                    .andExpect(queryParam("reprt_code", "11011"))
                    .andRespond(withSuccess(dividendJson(), MediaType.APPLICATION_JSON));

            DartDividendResponse response = dartApiClient.getDividendDisclosure(CORP_CODE);

            assertThat(response.bizYear()).isEqualTo(lastYear);
            assertThat(response.receiptNo()).isEqualTo("20260310002820");
            assertThat(response.settlementDate()).isEqualTo("2025-12-31");
            assertThat(response.indicators()).extracting(DartDividendResponse.DividendIndicator::label).containsExactly(
                    "주당 현금배당금(원)", "현금배당수익률(%)", "(연결)현금배당성향(%)", "현금배당금총액(백만원)",
                    "(연결)당기순이익(백만원)");
            DartDividendResponse.DividendIndicator dps = response.indicators().get(0);
            assertThat(dps.stockKind()).isEqualTo("보통주");
            assertThat(dps.currentValue()).isEqualTo(1668.0);
            assertThat(dps.previousValue()).isEqualTo(1446.0);
            assertThat(dps.beforePreviousValue()).isEqualTo(1444.0);
            assertThat(response.indicators().get(2).stockKind()).isNull();
            mockServer.verify();
        }

        @Test
        @DisplayName("작년 사업보고서가 아직 없으면 재작년 사업보고서로 대체한다")
        void fallsBackToTwoYearsAgo_whenLastYearIsMissing() {
            int lastYear = LocalDate.now().getYear() - 1;
            mockServer.expect(requestTo(Matchers.startsWith(DIVIDEND_URL)))
                    .andExpect(queryParam("bsns_year", String.valueOf(lastYear)))
                    .andRespond(withSuccess("""
                            {"status":"013","message":"조회된 데이타가 없습니다."}""", MediaType.APPLICATION_JSON));
            mockServer.expect(requestTo(Matchers.startsWith(DIVIDEND_URL)))
                    .andExpect(queryParam("bsns_year", String.valueOf(lastYear - 1)))
                    .andRespond(withSuccess(dividendJson(), MediaType.APPLICATION_JSON));

            DartDividendResponse response = dartApiClient.getDividendDisclosure(CORP_CODE);

            assertThat(response.bizYear()).isEqualTo(lastYear - 1);
            assertThat(response.indicators()).isNotEmpty();
            mockServer.verify();
        }

        @Test
        @DisplayName("두 해 모두 공시가 없으면 빈 지표 목록을 돌려준다")
        void returnsEmpty_whenNoDisclosure() {
            mockServer.expect(org.springframework.test.web.client.ExpectedCount.twice(), requestTo(Matchers.startsWith(DIVIDEND_URL)))
                    .andRespond(withSuccess("""
                            {"status":"013","message":"조회된 데이타가 없습니다."}""", MediaType.APPLICATION_JSON));

            DartDividendResponse response = dartApiClient.getDividendDisclosure(CORP_CODE);

            assertThat(response.indicators()).isEmpty();
            assertThat(response.receiptNo()).isNull();
        }

        @Test
        @DisplayName("DART 원문 주소는 접수번호로 만든다")
        void viewerUrl_buildsFromReceiptNo() {
            assertThat(DartApiClient.viewerUrl("20260310002820"))
                    .isEqualTo("https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20260310002820");
            assertThat(DartApiClient.viewerUrl(null)).isNull();
        }
    }

    @Nested
    @DisplayName("가장 최근 공시됐을 법한 분기 계산 (mostRecentLikelyAvailableQuarter)")
    class MostRecentLikelyAvailableQuarter {

        @ParameterizedTest(name = "{0} 기준이면 {1}년 reportCode={2}를 고른다")
        @DisplayName("공시 법정기한(45일) 경계값에 따라 올바른 보고서 종류를 고른다")
        @CsvSource({
                "2026-11-15, 2026, 11014",  // 3분기 기한 당일 → 올해 3분기
                "2026-11-14, 2026, 11012",  // 3분기 기한 하루 전 → 아직 반기
                "2026-08-15, 2026, 11012",  // 반기 기한 당일 → 올해 반기
                "2026-08-14, 2026, 11013",  // 반기 기한 하루 전 → 아직 1분기
                "2026-05-16, 2026, 11013",  // 1분기 기한 당일 → 올해 1분기
                "2026-05-15, 2025, 11014",  // 1분기 기한 하루 전 → 작년 3분기로 대체
                "2026-01-01, 2025, 11014",  // 연초 → 작년 3분기로 대체
        })
        void picksCorrectReportByDeadlineBoundary(String today, int expectedYear, String expectedReportCode) {
            DartApiClient.ReportPeriod result =
                    dartApiClient.mostRecentLikelyAvailableQuarter(LocalDate.parse(today));

            assertThat(result.year()).isEqualTo(expectedYear);
            assertThat(result.reportCode()).isEqualTo(expectedReportCode);
        }

        @ParameterizedTest(name = "{0}이면 작년 사업보고서 우선 = {1}")
        @DisplayName("4월 1일~5월 15일에는 작년 사업보고서(3월 말 공시)를 작년 3분기보다 먼저 찾는다(2026-10-12 코드리뷰)")
        @CsvSource({
                "2026-03-31, false",
                "2026-04-01, true",
                "2026-05-15, true",
                "2026-05-16, false",
                "2026-10-12, false",
        })
        void annualReportFirstWindow(String today, boolean expected) {
            assertThat(dartApiClient.isAnnualReportFirst(LocalDate.parse(today))).isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("최근 분기 실적 조회 (getRecentQuarterlyFinancials)")
    class GetRecentQuarterlyFinancials {

        @Test
        @DisplayName("날짜 기준으로 고른 분기 보고서에 데이터가 있으면 그것만 조회하고 끝난다")
        void success_usesLikelyQuarterOnFirstTry() {
            DartApiClient.ReportPeriod expected =
                    dartApiClient.mostRecentLikelyAvailableQuarter(LocalDate.now());

            mockServer.expect(requestTo(Matchers.startsWith(API_URL)))
                    .andExpect(queryParam("bsns_year", String.valueOf(expected.year())))
                    .andExpect(queryParam("reprt_code", expected.reportCode()))
                    .andExpect(queryParam("fs_div", "CFS"))
                    .andRespond(withSuccess(fullAccountsJson(), MediaType.APPLICATION_JSON));

            DartFinancialResponse response = dartApiClient.getRecentQuarterlyFinancials(CORP_CODE);

            assertThat(response.revenue()).isEqualTo(333605938000000L);
            mockServer.verify();
        }

        @Test
        @DisplayName("날짜 기준 분기에 데이터가 없으면 같은 해의 더 이전 분기를 마저 시도한 뒤 작년 연간 사업보고서로 대체한다")
        void fallsBackToLastYearAnnual_whenLikelyQuarterHasNoData() {
            // 실제 운영 코드(getRecentQuarterlyFinancials)는 날짜로 고른 분기부터 시작해
            // 3분기→반기→1분기 순서(QUARTERLY_REPORT_CODES_NEWEST_FIRST)를 끝까지 마저 시도한 뒤에야
            // 작년 연간으로 대체한다. 테스트 실행 시점(LocalDate.now())에 따라 시작 분기가
            // 반기·1분기 등으로 달라지므로, 시작 분기부터 1분기까지 남은 모든 분기를 동적으로
            // mock해야 실행 날짜와 무관하게 항상 통과한다.
            List<String> quarterlyReportCodesNewestFirst = List.of("11014", "11012", "11013");
            DartApiClient.ReportPeriod expected =
                    dartApiClient.mostRecentLikelyAvailableQuarter(LocalDate.now());
            String emptyJson = """
                    {"status":"013","message":"조회된 데이타가 없습니다."}""";

            int startIndex = quarterlyReportCodesNewestFirst.indexOf(expected.reportCode());
            for (int i = startIndex; i < quarterlyReportCodesNewestFirst.size(); i++) {
                String reportCode = quarterlyReportCodesNewestFirst.get(i);
                mockServer.expect(requestTo(Matchers.startsWith(API_URL)))
                        .andExpect(queryParam("bsns_year", String.valueOf(expected.year())))
                        .andExpect(queryParam("reprt_code", reportCode))
                        .andExpect(queryParam("fs_div", "CFS"))
                        .andRespond(withSuccess(emptyJson, MediaType.APPLICATION_JSON));
                mockServer.expect(requestTo(Matchers.startsWith(API_URL)))
                        .andExpect(queryParam("bsns_year", String.valueOf(expected.year())))
                        .andExpect(queryParam("reprt_code", reportCode))
                        .andExpect(queryParam("fs_div", "OFS"))
                        .andRespond(withSuccess(emptyJson, MediaType.APPLICATION_JSON));
            }

            // 작년 연간(11011) 사업보고서 대체 - CFS 성공
            int lastYear = LocalDate.now().getYear() - 1;
            mockServer.expect(requestTo(Matchers.startsWith(API_URL)))
                    .andExpect(queryParam("bsns_year", String.valueOf(lastYear)))
                    .andExpect(queryParam("reprt_code", "11011"))
                    .andExpect(queryParam("fs_div", "CFS"))
                    .andRespond(withSuccess(fullAccountsJson(), MediaType.APPLICATION_JSON));

            DartFinancialResponse response = dartApiClient.getRecentQuarterlyFinancials(CORP_CODE);

            assertThat(response.revenue()).isEqualTo(333605938000000L);
            assertThat(response.bizYear()).isEqualTo(lastYear);
            // 시작 분기부터 1분기까지 전부 실패한 뒤 연간으로 대체되고, 더 이상 시도하지 않는다.
            mockServer.verify();
        }
    }

    @Nested
    @DisplayName("자본변동 공시 조회 (getCapitalChangeDecisions)")
    class GetCapitalChangeDecisions {

        @Test
        @DisplayName("유상증자를 요청하면 piicDecsn.json 엔드포인트로 최근 2년 범위를 조회한다")
        void success_paidIncrease_callsCorrectEndpointWithLookbackRange() {
            LocalDate today = LocalDate.now();
            mockServer.expect(requestTo(Matchers.startsWith("http://test-dart/piicDecsn.json")))
                    .andExpect(queryParam("corp_code", CORP_CODE))
                    .andExpect(queryParam("bgn_de", today.minusYears(2).toString().replace("-", "")))
                    .andExpect(queryParam("end_de", today.toString().replace("-", "")))
                    .andRespond(withSuccess("""
                            {"status":"000","message":"정상","list":[
                              {"bddd":"20260101","nstk_ostk_cnt":"1000000","ic_mthn":"제3자배정증자"}
                            ]}""", MediaType.APPLICATION_JSON));

            var items = dartApiClient.getCapitalChangeDecisions(CORP_CODE, "유상증자");

            assertThat(items).hasSize(1);
            assertThat(items.get(0)).containsEntry("ic_mthn", "제3자배정증자");
            mockServer.verify();
        }

        @Test
        @DisplayName("무상증자를 요청하면 fricDecsn.json 엔드포인트를 조회한다")
        void success_freeIncrease_callsCorrectEndpoint() {
            mockServer.expect(requestTo(Matchers.startsWith("http://test-dart/fricDecsn.json")))
                    .andRespond(withSuccess("""
                            {"status":"000","message":"정상","list":[
                              {"bddd":"20260101","nstk_ostk_cnt":"500000"}
                            ]}""", MediaType.APPLICATION_JSON));

            var items = dartApiClient.getCapitalChangeDecisions(CORP_CODE, "무상증자");

            assertThat(items).hasSize(1);
            mockServer.verify();
        }

        @Test
        @DisplayName("최근 2년간 공시가 없으면(status 013) 예외 없이 빈 리스트를 반환한다")
        void emptyList_whenNoDisclosuresInLookbackRange() {
            mockServer.expect(requestTo(Matchers.startsWith("http://test-dart/piicDecsn.json")))
                    .andRespond(withSuccess("""
                            {"status":"013","message":"조회된 데이타가 없습니다."}""", MediaType.APPLICATION_JSON));

            var items = dartApiClient.getCapitalChangeDecisions(CORP_CODE, "유상증자");

            assertThat(items).isEmpty();
        }

        @Test
        @DisplayName("결과가 MAX_DISCLOSURE_ITEMS(5)개를 넘으면 최신 5개까지만 잘라 반환한다")
        void success_limitsToMaxDisclosureItems() {
            String sixItemsJson = "{\"status\":\"000\",\"message\":\"정상\",\"list\":["
                    + java.util.stream.IntStream.rangeClosed(1, 6)
                            .mapToObj(i -> "{\"bddd\":\"2026010%d\"}".formatted(i))
                            .collect(java.util.stream.Collectors.joining(","))
                    + "]}";
            mockServer.expect(requestTo(Matchers.startsWith("http://test-dart/piicDecsn.json")))
                    .andRespond(withSuccess(sixItemsJson, MediaType.APPLICATION_JSON));

            var items = dartApiClient.getCapitalChangeDecisions(CORP_CODE, "유상증자");

            assertThat(items).hasSize(5);
        }
    }

    @Nested
    @DisplayName("소유권(최대주주 현황/변동) 공시 조회 (getOwnershipInfo)")
    class GetOwnershipInfo {

        @Test
        @DisplayName("현황(hyslrSttus.json)에 실질 데이터가 있으면 그대로 반환하고 연간으로 대체하지 않는다")
        void success_status_usesQuarterlyWhenHasRealData() {
            DartApiClient.ReportPeriod expected = dartApiClient.mostRecentLikelyAvailableQuarter(LocalDate.now());
            mockServer.expect(requestTo(Matchers.startsWith("http://test-dart/hyslrSttus.json")))
                    .andExpect(queryParam("bsns_year", String.valueOf(expected.year())))
                    .andExpect(queryParam("reprt_code", expected.reportCode()))
                    .andRespond(withSuccess("""
                            {"status":"000","message":"정상","list":[
                              {"nm":"이재용","relate":"본인","bsis_posesn_stock_qota_rt":"1.63","trmend_posesn_stock_qota_rt":"1.63","stlm_dt":"2026-06-30"}
                            ]}""", MediaType.APPLICATION_JSON));

            var items = dartApiClient.getOwnershipInfo(CORP_CODE, "현황");

            assertThat(items).hasSize(1);
            assertThat(items.get(0)).containsEntry("nm", "이재용");
            mockServer.verify();
        }

        @Test
        @DisplayName("변동(hyslrChgSttus.json)이 분기 기준으로 빈 값뿐이면 작년 연간 사업보고서로 대체한다")
        void success_change_fallsBackToAnnual_whenQuarterlyIsBlank() {
            DartApiClient.ReportPeriod expected = dartApiClient.mostRecentLikelyAvailableQuarter(LocalDate.now());
            // 행은 오지만 식별용 필드(boilerplate)만 채워진 "실질 데이터 없음" 응답
            mockServer.expect(requestTo(Matchers.startsWith("http://test-dart/hyslrChgSttus.json")))
                    .andExpect(queryParam("bsns_year", String.valueOf(expected.year())))
                    .andExpect(queryParam("reprt_code", expected.reportCode()))
                    .andRespond(withSuccess("""
                            {"status":"000","message":"정상","list":[
                              {"rcept_no":"20260101000001","corp_code":"%s","corp_name":"삼성전자","stlm_dt":"2026-06-30"}
                            ]}""".formatted(CORP_CODE), MediaType.APPLICATION_JSON));

            int lastYear = LocalDate.now().getYear() - 1;
            mockServer.expect(requestTo(Matchers.startsWith("http://test-dart/hyslrChgSttus.json")))
                    .andExpect(queryParam("bsns_year", String.valueOf(lastYear)))
                    .andExpect(queryParam("reprt_code", "11011"))
                    .andRespond(withSuccess("""
                            {"status":"000","message":"정상","list":[
                              {"change_on":"2025-05-01","mxmm_shrholdr_nm":"이재용","qota_rt":"1.63","change_cause":"장내매수"}
                            ]}""", MediaType.APPLICATION_JSON));

            var items = dartApiClient.getOwnershipInfo(CORP_CODE, "변동");

            assertThat(items).hasSize(1);
            assertThat(items.get(0)).containsEntry("mxmm_shrholdr_nm", "이재용");
            mockServer.verify();
        }
    }

    @Nested
    @DisplayName("범용 공시 조회 (getDisclosureInfo, 70여 개 레지스트리)")
    class GetDisclosureInfo {

        @Test
        @DisplayName("등록되지 않은 disclosureType이면 API를 호출하지 않고 빈 리스트를 반환한다")
        void unregisteredLabel_returnsEmptyListWithoutCallingApi() {
            var items = dartApiClient.getDisclosureInfo(CORP_CODE, "존재하지않는라벨");

            assertThat(items).isEmpty();
            mockServer.verify();
        }

        @Test
        @DisplayName("YEAR_REPORT 방식 항목(배당사항)은 분기→연간 폴백 로직을 그대로 탄다")
        void yearReportType_fallsBackToAnnualWhenQuarterlyBlank() {
            DartApiClient.ReportPeriod expected = dartApiClient.mostRecentLikelyAvailableQuarter(LocalDate.now());
            mockServer.expect(requestTo(Matchers.startsWith("http://test-dart/alotMatter.json")))
                    .andExpect(queryParam("bsns_year", String.valueOf(expected.year())))
                    .andExpect(queryParam("reprt_code", expected.reportCode()))
                    .andRespond(withSuccess("""
                            {"status":"013","message":"조회된 데이타가 없습니다."}""", MediaType.APPLICATION_JSON));

            int lastYear = LocalDate.now().getYear() - 1;
            mockServer.expect(requestTo(Matchers.startsWith("http://test-dart/alotMatter.json")))
                    .andExpect(queryParam("bsns_year", String.valueOf(lastYear)))
                    .andExpect(queryParam("reprt_code", "11011"))
                    .andRespond(withSuccess("""
                            {"status":"000","message":"정상","list":[
                              {"se":"주당 현금배당금(원)","thstrm":"361"}
                            ]}""", MediaType.APPLICATION_JSON));

            var items = dartApiClient.getDisclosureInfo(CORP_CODE, "배당사항");

            assertThat(items).hasSize(1);
            mockServer.verify();
        }

        @Test
        @DisplayName("DATE_RANGE 방식 항목(소송제기)은 최근 2년 범위로 조회한다")
        void dateRangeType_success() {
            LocalDate today = LocalDate.now();
            mockServer.expect(requestTo(Matchers.startsWith("http://test-dart/lwstLg.json")))
                    .andExpect(queryParam("corp_code", CORP_CODE))
                    .andExpect(queryParam("bgn_de", today.minusYears(2).toString().replace("-", "")))
                    .andExpect(queryParam("end_de", today.toString().replace("-", "")))
                    .andRespond(withSuccess("""
                            {"status":"000","message":"정상","list":[
                              {"bddd":"20260101","ls_pp":"손해배상청구소송"}
                            ]}""", MediaType.APPLICATION_JSON));

            var items = dartApiClient.getDisclosureInfo(CORP_CODE, "소송제기");

            assertThat(items).hasSize(1);
            mockServer.verify();
        }

        @Test
        @DisplayName("단일회사재무지표는 idx_cl_code(수익성/안정성)를 함께 보내 두 카테고리를 합쳐 반환한다")
        void financialIndex_sendsIdxClCodeAndMergesTwoCategories() {
            // 실제 라이브 테스트(2026-08-05)로 확인된 사실 — 이 엔드포인트는 idx_cl_code 없이
            // 부르면 DART가 status "100"(필수값 누락)으로 거부하고, 한 번에 한 카테고리만 준다.
            DartApiClient.ReportPeriod expected = dartApiClient.mostRecentLikelyAvailableQuarter(LocalDate.now());
            mockServer.expect(requestTo(Matchers.startsWith("http://test-dart/fnlttSinglIndx.json")))
                    .andExpect(queryParam("idx_cl_code", "M210000"))
                    .andExpect(queryParam("bsns_year", String.valueOf(expected.year())))
                    .andExpect(queryParam("reprt_code", expected.reportCode()))
                    .andRespond(withSuccess("""
                            {"status":"000","message":"정상","list":[
                              {"idx_cl_code":"M210000","idx_cl_nm":"수익성지표","idx_nm":"ROE","idx_val":"10.233"}
                            ]}""", MediaType.APPLICATION_JSON));
            mockServer.expect(requestTo(Matchers.startsWith("http://test-dart/fnlttSinglIndx.json")))
                    .andExpect(queryParam("idx_cl_code", "M220000"))
                    .andRespond(withSuccess("""
                            {"status":"000","message":"정상","list":[
                              {"idx_cl_code":"M220000","idx_cl_nm":"안정성지표","idx_nm":"부채비율","idx_val":"27.09"}
                            ]}""", MediaType.APPLICATION_JSON));

            var items = dartApiClient.getDisclosureInfo(CORP_CODE, "단일회사재무지표");

            assertThat(items).hasSize(2);
            assertThat(items.stream().map(item -> item.get("idx_nm"))).containsExactlyInAnyOrder("ROE", "부채비율");
            mockServer.verify();
        }

        @Test
        @DisplayName("CORP_CODE_ONLY 방식 항목(대량보유상황보고)은 기간·연도 없이 corp_code만으로 조회한다")
        void corpCodeOnlyType_success() {
            mockServer.expect(requestTo(Matchers.startsWith("http://test-dart/majorstock.json")))
                    .andExpect(queryParam("corp_code", CORP_CODE))
                    .andRespond(withSuccess("""
                            {"status":"000","message":"정상","list":[
                              {"repror":"국민연금공단","stkqy_irds":"1000000"}
                            ]}""", MediaType.APPLICATION_JSON));

            var items = dartApiClient.getDisclosureInfo(CORP_CODE, "대량보유상황보고");

            assertThat(items).hasSize(1);
            mockServer.verify();
        }
    }

    @Nested
    @DisplayName("회사명 → corp_code 매핑 (resolveCorpCodeByName)")
    class ResolveCorpCodeByName {

        @Test
        @DisplayName("null/빈 문자열이면 목록을 내려받지 않고 즉시 빈 값을 반환한다")
        void blank_returnsEmptyWithoutDownload() {
            assertThat(dartApiClient.resolveCorpCodeByName(null)).isEmpty();
            assertThat(dartApiClient.resolveCorpCodeByName("  ")).isEmpty();
            mockServer.verify();
        }

        @Test
        @DisplayName("상장/비상장 회사명 모두 corp_code를 찾되, 이름이 겹치면 상장사를 우선한다")
        void success_resolvesListedAndUnlisted_prefersListedOnNameCollision() throws IOException {
            mockServer.expect(requestTo(Matchers.startsWith(CORP_CODE_URL)))
                    .andRespond(withSuccess(sampleCorpCodeZip(), MediaType.APPLICATION_OCTET_STREAM));

            Optional<String> found = dartApiClient.resolveCorpCodeByName("삼성전자");
            Optional<String> unlisted = dartApiClient.resolveCorpCodeByName("비상장회사");
            Optional<String> notFound = dartApiClient.resolveCorpCodeByName("존재안함");
            Optional<String> collision = dartApiClient.resolveCorpCodeByName("동명기업");

            assertThat(found).contains(CORP_CODE);
            // 비상장 회사도 공시 조회(합병·최대주주 등) 목적으로는 여전히 찾을 수 있어야 한다.
            assertThat(unlisted).contains("00999999");
            assertThat(notFound).isEmpty();
            // 상장사와 비상장사 이름이 같으면 상장사의 corp_code(00888888)가 이긴다.
            assertThat(collision).contains("00888888");
        }

        @Test
        @DisplayName("여러 번 조회해도 목록 다운로드는 최초 1회만 일어난다(메모리 캐싱)")
        void success_downloadsOnlyOnceAndCaches() throws IOException {
            mockServer.expect(requestTo(Matchers.startsWith(CORP_CODE_URL)))
                    .andRespond(withSuccess(sampleCorpCodeZip(), MediaType.APPLICATION_OCTET_STREAM));

            dartApiClient.resolveCorpCodeByName("삼성전자");
            dartApiClient.resolveCorpCodeByName("삼성전자");
            dartApiClient.resolveCorpCodeByName("비상장회사");

            // expect()가 1번만 등록됐으므로, 2번째 이상의 다운로드 요청이 있었다면 여기서 실패한다.
            mockServer.verify();
        }
    }

    @Test
    @DisplayName("상장 종목 추천은 이름 일부와 코드 접두어를 찾고 요청한 개수만 반환한다")
    void searchListedStocks_matchesNameAndCodeWithinLimit() throws IOException {
        mockServer.expect(requestTo(Matchers.startsWith(CORP_CODE_URL)))
                .andRespond(withSuccess(sampleCorpCodeZip(), MediaType.APPLICATION_OCTET_STREAM));

        assertThat(dartApiClient.searchListedStocks("삼성", 8))
                .extracting(ListedStock::stockCode)
                .containsExactly("005930", "005935");
        assertThat(dartApiClient.searchListedStocks("005", 1))
                .extracting(ListedStock::stockCode)
                .containsExactly("005930");
        assertThat(dartApiClient.searchListedStocks("비상장", 8)).isEmpty();
        mockServer.verify();
    }

    @Test
    @DisplayName("DART 종목코드로 회사를 찾고, 등록되지 않은 우선주는 보통주 회사 코드로 연결한다")
    void resolveCorpCodeByStockCode_handlesPreferredStock() throws IOException {
        mockServer.expect(requestTo(Matchers.startsWith(CORP_CODE_URL)))
                .andRespond(withSuccess(sampleCorpCodeZip(), MediaType.APPLICATION_OCTET_STREAM));

        assertThat(dartApiClient.resolveCorpCodeByStockCode("005930")).contains(CORP_CODE);
        assertThat(dartApiClient.resolveCorpCodeByStockCode("005935")).contains("00126381");
        ReflectionTestUtils.setField(dartApiClient, "corpCodeByStockCode", Map.of("005930", CORP_CODE));
        assertThat(dartApiClient.resolveCorpCodeByStockCode("005935")).contains(CORP_CODE);
        assertThat(dartApiClient.resolveCorpCodeByStockCode("123455")).isEmpty();
        mockServer.verify();
    }

    @Test
    @DisplayName("DART 재무 조회 제한 020은 자료 없음으로 처리하지 않는다")
    void financialRateLimit_isReported() {
        mockServer.expect(requestTo(Matchers.startsWith(API_URL)))
                .andRespond(withSuccess("{\"status\":\"020\",\"message\":\"요청 제한\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> dartApiClient.getFinancials(new DartFinancialRequest(CORP_CODE, 2025)))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.DART_RATE_LIMIT_EXCEEDED);
        mockServer.verify();
    }

    @Test
    @DisplayName("DART 공시 조회 제한 020은 빈 배당 목록으로 처리하지 않는다")
    void disclosureRateLimit_isReported() {
        mockServer.expect(requestTo(Matchers.startsWith("http://test-dart/alotMatter.json")))
                .andRespond(withSuccess("{\"status\":\"020\",\"message\":\"요청 제한\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> dartApiClient.getDisclosureInfo(CORP_CODE, "배당사항"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.DART_RATE_LIMIT_EXCEEDED);
        mockServer.verify();
    }

    private String fullAccountsJson() {
        return """
                {"status":"000","message":"정상","list":[
                  {"account_nm":"매출액","thstrm_amount":"333605938000000"},
                  {"account_nm":"영업이익","thstrm_amount":"43601051000000"},
                  {"account_nm":"당기순이익","thstrm_amount":"45206805000000"},
                  {"account_nm":"자산총계","thstrm_amount":"566942110000000"},
                  {"account_nm":"부채총계","thstrm_amount":"130621773000000"},
                  {"account_nm":"자본총계","thstrm_amount":"436320337000000"}
                ]}""";
    }

    // DART corpCode.xml 응답을 흉내내는 zip 바이너리를 메모리에서 즉석으로 만든다 — 실제 파일을
    // 저장소에 두지 않고도 파싱 로직(zip 해제 → XML 파싱 → stock_code 있는 것만 채택)을 검증한다.
    private byte[] sampleCorpCodeZip() throws IOException {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <result>
                    <list>
                        <corp_code>%s</corp_code>
                        <corp_name>삼성전자</corp_name>
                        <stock_code>005930</stock_code>
                        <modify_date>20260101</modify_date>
                    </list>
                    <list>
                        <corp_code>00999999</corp_code>
                        <corp_name>비상장회사</corp_name>
                        <stock_code></stock_code>
                        <modify_date>20260101</modify_date>
                    </list>
                    <list>
                        <corp_code>00126381</corp_code>
                        <corp_name>삼성전자우</corp_name>
                        <stock_code>005935</stock_code>
                        <modify_date>20260101</modify_date>
                    </list>
                    <list>
                        <corp_code>00777777</corp_code>
                        <corp_name>동명기업</corp_name>
                        <stock_code></stock_code>
                        <modify_date>20260101</modify_date>
                    </list>
                    <list>
                        <corp_code>00888888</corp_code>
                        <corp_name>동명기업</corp_name>
                        <stock_code>000010</stock_code>
                        <modify_date>20260101</modify_date>
                    </list>
                </result>
                """.formatted(CORP_CODE);

        ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        try (ZipOutputStream zipStream = new ZipOutputStream(byteStream)) {
            zipStream.putNextEntry(new ZipEntry("CORPCODE.xml"));
            zipStream.write(xml.getBytes(StandardCharsets.UTF_8));
            zipStream.closeEntry();
        }
        return byteStream.toByteArray();
    }
}
