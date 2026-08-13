package com.teamfp.aistock.infra.dart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
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

import com.teamfp.aistock.infra.dart.dto.DartFinancialRequest;
import com.teamfp.aistock.infra.dart.dto.DartFinancialResponse;

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
        @DisplayName("날짜 기준 분기에 데이터가 없으면 작년 연간 사업보고서로 대체하고, 그 이상은 더 시도하지 않는다")
        void fallsBackToLastYearAnnual_whenLikelyQuarterHasNoData() {
            DartApiClient.ReportPeriod expected =
                    dartApiClient.mostRecentLikelyAvailableQuarter(LocalDate.now());
            String emptyJson = """
                    {"status":"013","message":"조회된 데이타가 없습니다."}""";

            // 날짜 기준 분기 시도 - CFS/OFS 둘 다 없음
            mockServer.expect(requestTo(Matchers.startsWith(API_URL)))
                    .andExpect(queryParam("bsns_year", String.valueOf(expected.year())))
                    .andExpect(queryParam("reprt_code", expected.reportCode()))
                    .andExpect(queryParam("fs_div", "CFS"))
                    .andRespond(withSuccess(emptyJson, MediaType.APPLICATION_JSON));
            mockServer.expect(requestTo(Matchers.startsWith(API_URL)))
                    .andExpect(queryParam("bsns_year", String.valueOf(expected.year())))
                    .andExpect(queryParam("reprt_code", expected.reportCode()))
                    .andExpect(queryParam("fs_div", "OFS"))
                    .andRespond(withSuccess(emptyJson, MediaType.APPLICATION_JSON));

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
            // 딱 3번(분기 CFS+OFS 실패, 연간 CFS 성공)만 호출되고 더 이상 시도하지 않는다.
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
