package com.teamfp.aistock.infra.ls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.teamfp.aistock.infra.ls.dto.LsFinancialRankingDto;
import com.teamfp.aistock.infra.ls.dto.LsInvestmentOpinionDto;
import com.teamfp.aistock.infra.ls.dto.LsMarketLiquidityDto;
import com.teamfp.aistock.infra.ls.dto.LsOverseasIndexDto;
import com.teamfp.aistock.infra.ls.dto.LsShareholderMeetingDto;
import java.util.Optional;

@ExtendWith(MockitoExtension.class)
class LsInvestInfoApiClientTest {

    private static final String INVESTINFO_URL = "http://test-ls/stock/investinfo";
    private static final String STOCK_CODE = "005930";

    @Mock
    private LsAccessTokenProvider accessTokenProvider;

    private MockRestServiceServer mockServer;
    private LsInvestInfoApiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();

        client = new LsInvestInfoApiClient(accessTokenProvider, builder);
        ReflectionTestUtils.setField(client, "investInfoUrl", INVESTINFO_URL);
    }

    @Nested
    @DisplayName("투자의견 조회 (getInvestmentOpinions, t3401)")
    class GetInvestmentOpinions {

        @Test
        @DisplayName("t3401OutBlock1 배열을 증권사별 의견/목표주가 변경 이력으로 파싱한다")
        void success_parsesOpinionRows() {
            when(accessTokenProvider.issueAccessToken()).thenReturn("test-token");
            mockServer.expect(requestTo(INVESTINFO_URL))
                    .andRespond(withSuccess("""
                            {"t3401OutBlock1":[
                              {"date":"20260805","tradname":"메리츠","nopn":"HOLD","bopn":"BUY","boga":24000,"noga":30000,"close":28500}
                            ]}""", MediaType.APPLICATION_JSON));

            List<LsInvestmentOpinionDto> result = client.getInvestmentOpinions(STOCK_CODE);

            assertThat(result).hasSize(1);
            LsInvestmentOpinionDto opinion = result.get(0);
            assertThat(opinion.getDate()).isEqualTo("20260805");
            assertThat(opinion.getSecuritiesFirm()).isEqualTo("메리츠");
            assertThat(opinion.getOpinionBefore()).isEqualTo("HOLD");
            assertThat(opinion.getOpinionAfter()).isEqualTo("BUY");
            assertThat(opinion.getTargetPriceBefore()).isEqualTo(24000L);
            assertThat(opinion.getTargetPriceAfter()).isEqualTo(30000L);
            assertThat(opinion.getClosePriceOnDate()).isEqualTo(28500L);
            mockServer.verify();
        }

        @Test
        @DisplayName("결과가 6건을 넘으면 최신 5건까지만 잘라 반환한다")
        void success_limitsToFiveItems() {
            when(accessTokenProvider.issueAccessToken()).thenReturn("test-token");
            String sixRows = "{\"t3401OutBlock1\":["
                    + java.util.stream.IntStream.rangeClosed(1, 6)
                            .mapToObj(i -> "{\"date\":\"2026080%d\",\"tradname\":\"증권사%d\"}".formatted(i, i))
                            .collect(java.util.stream.Collectors.joining(","))
                    + "]}";
            mockServer.expect(requestTo(INVESTINFO_URL))
                    .andRespond(withSuccess(sixRows, MediaType.APPLICATION_JSON));

            List<LsInvestmentOpinionDto> result = client.getInvestmentOpinions(STOCK_CODE);

            assertThat(result).hasSize(5);
        }

        @Test
        @DisplayName("t3401OutBlock1이 없으면 빈 리스트를 반환한다")
        void empty_whenOutBlockMissing() {
            when(accessTokenProvider.issueAccessToken()).thenReturn("test-token");
            mockServer.expect(requestTo(INVESTINFO_URL))
                    .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

            List<LsInvestmentOpinionDto> result = client.getInvestmentOpinions(STOCK_CODE);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("주주총회 일정 조회 (getShareholderMeetingSchedule, t3202)")
    class GetShareholderMeetingSchedule {

        @Test
        @DisplayName("upgu=09(주주총회)만 남기고 배당(03)·유상증자(01) 등 나머지 13종은 걸러낸다 — DART 도구와의 겹침 방지 핵심 동작")
        void success_filtersOnlyShareholderMeeting() {
            when(accessTokenProvider.issueAccessToken()).thenReturn("test-token");
            mockServer.expect(requestTo(INVESTINFO_URL))
                    .andRespond(withSuccess("""
                            {"t3202OutBlock":[
                              {"recdt":"20260315","upunm":"주주총회","upgu":"09"},
                              {"recdt":"20260201","upunm":"배당","upgu":"03"},
                              {"recdt":"20260110","upunm":"유상증자","upgu":"01"}
                            ]}""", MediaType.APPLICATION_JSON));

            List<LsShareholderMeetingDto> result = client.getShareholderMeetingSchedule(STOCK_CODE);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getDate()).isEqualTo("20260315");
            assertThat(result.get(0).getEventName()).isEqualTo("주주총회");
            mockServer.verify();
        }

        @Test
        @DisplayName("기준일이 00000000인(실데이터 없는) 주주총회 행은 걸러낸다")
        void success_filtersOutZeroDateRows() {
            when(accessTokenProvider.issueAccessToken()).thenReturn("test-token");
            mockServer.expect(requestTo(INVESTINFO_URL))
                    .andRespond(withSuccess("""
                            {"t3202OutBlock":[
                              {"recdt":"00000000","upunm":"주주총회","upgu":"09"},
                              {"recdt":"20250527","upunm":"주주총회","upgu":"09"}
                            ]}""", MediaType.APPLICATION_JSON));

            List<LsShareholderMeetingDto> result = client.getShareholderMeetingSchedule(STOCK_CODE);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getDate()).isEqualTo("20250527");
        }

        @Test
        @DisplayName("주주총회 항목이 하나도 없으면 빈 리스트를 반환한다")
        void empty_whenNoShareholderMeetingRows() {
            when(accessTokenProvider.issueAccessToken()).thenReturn("test-token");
            mockServer.expect(requestTo(INVESTINFO_URL))
                    .andRespond(withSuccess("""
                            {"t3202OutBlock":[
                              {"recdt":"20260201","upunm":"배당","upgu":"03"}
                            ]}""", MediaType.APPLICATION_JSON));

            List<LsShareholderMeetingDto> result = client.getShareholderMeetingSchedule(STOCK_CODE);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("재무순위종합 조회 (getFinancialRanking, t3341)")
    class GetFinancialRanking {

        @Test
        @DisplayName("t3341OutBlock1 배열을 재무순위 랭킹으로 파싱한다")
        void success_parsesRankingRows() {
            when(accessTokenProvider.issueAccessToken()).thenReturn("test-token");
            mockServer.expect(requestTo(INVESTINFO_URL))
                    .andRespond(withSuccess("""
                            {"t3341OutBlock1":[
                              {"rank":1,"hname":"삼성전자","shcode":"005930","roe":15.2,"per":12.5,"pbr":1.3,"salesgrowth":5.4}
                            ]}""", MediaType.APPLICATION_JSON));

            List<LsFinancialRankingDto> result = client.getFinancialRanking("8");

            assertThat(result).hasSize(1);
            LsFinancialRankingDto item = result.get(0);
            assertThat(item.getStockName()).isEqualTo("삼성전자");
            assertThat(item.getStockCode()).isEqualTo("005930");
            assertThat(item.getRoe()).isEqualTo(15.2);
            mockServer.verify();
        }

        @Test
        @DisplayName("결과가 11건을 넘으면 상위 10건까지만 잘라 반환한다")
        void success_limitsToTenItems() {
            when(accessTokenProvider.issueAccessToken()).thenReturn("test-token");
            String rows = "{\"t3341OutBlock1\":["
                    + java.util.stream.IntStream.rangeClosed(1, 11)
                            .mapToObj(i -> "{\"rank\":%d,\"hname\":\"종목%d\",\"shcode\":\"00000%d\"}".formatted(i, i, i))
                            .collect(java.util.stream.Collectors.joining(","))
                    + "]}";
            mockServer.expect(requestTo(INVESTINFO_URL))
                    .andRespond(withSuccess(rows, MediaType.APPLICATION_JSON));

            List<LsFinancialRankingDto> result = client.getFinancialRanking("8");

            assertThat(result).hasSize(10);
        }
    }

    @Nested
    @DisplayName("해외지수 조회 (getOverseasIndex, t3521)")
    class GetOverseasIndex {

        @Test
        @DisplayName("t3521OutBlock을 해외지수 시세로 파싱한다")
        void success_parsesIndex() {
            when(accessTokenProvider.issueAccessToken()).thenReturn("test-token");
            mockServer.expect(requestTo(INVESTINFO_URL))
                    .andRespond(withSuccess("""
                            {"t3521OutBlock":{"symbol":"DJI@DJI","hname":"다우 산업","close":"54036.93","change":"151.83","diff":"0.28","date":"20260807"}}""",
                            MediaType.APPLICATION_JSON));

            Optional<LsOverseasIndexDto> result = client.getOverseasIndex("S", "DJI@DJI");

            assertThat(result).isPresent();
            assertThat(result.get().getName()).isEqualTo("다우 산업");
            assertThat(result.get().getPrice()).isEqualTo(54036.93);
            mockServer.verify();
        }

        @Test
        @DisplayName("t3521OutBlock이 없으면 빈 값을 반환한다")
        void empty_whenOutBlockMissing() {
            when(accessTokenProvider.issueAccessToken()).thenReturn("test-token");
            mockServer.expect(requestTo(INVESTINFO_URL))
                    .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

            Optional<LsOverseasIndexDto> result = client.getOverseasIndex("S", "DJI@DJI");

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("증시주변자금추이 조회 (getRecentMarketLiquidityTrend, t8428)")
    class GetRecentMarketLiquidityTrend {

        @Test
        @DisplayName("t8428OutBlock1 배열을 고객예탁금/신용융자잔고 추이로 파싱한다")
        void success_parsesLiquidityRows() {
            when(accessTokenProvider.issueAccessToken()).thenReturn("test-token");
            mockServer.expect(requestTo(INVESTINFO_URL))
                    .andRespond(withSuccess("""
                            {"t8428OutBlock1":[
                              {"date":"20260806","custmoney":1040712,"trjango":287940}
                            ]}""", MediaType.APPLICATION_JSON));

            List<LsMarketLiquidityDto> result = client.getRecentMarketLiquidityTrend();

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getCustomerDepositAmount()).isEqualTo(1040712L);
            assertThat(result.get(0).getMarginLoanAmount()).isEqualTo(287940L);
            mockServer.verify();
        }

        @Test
        @DisplayName("t8428OutBlock1이 없으면 빈 리스트를 반환한다")
        void empty_whenOutBlockMissing() {
            when(accessTokenProvider.issueAccessToken()).thenReturn("test-token");
            mockServer.expect(requestTo(INVESTINFO_URL))
                    .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

            List<LsMarketLiquidityDto> result = client.getRecentMarketLiquidityTrend();

            assertThat(result).isEmpty();
        }
    }
}
