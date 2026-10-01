package com.teamfp.aistock.infra.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import java.util.Map;
import java.util.Optional;

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

import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;
import com.teamfp.aistock.infra.marketdata.dto.CurrentPriceDetailDto;
import com.teamfp.aistock.infra.marketdata.dto.RankingItemDto;

@ExtendWith(MockitoExtension.class)
class HighItemApiClientTest {

    private static final String HIGH_ITEM_URL = "http://test-ls/stock/high-item";

    @Mock
    private MarketDataAccessTokenProvider accessTokenProvider;

    private MockRestServiceServer mockServer;
    private HighItemApiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();

        client = new HighItemApiClient(accessTokenProvider, java.util.Optional.empty(), builder);
        ReflectionTestUtils.setField(client, "highItemUrl", HIGH_ITEM_URL);
    }

    @Nested
    @DisplayName("등락율상위 조회 (getTopPriceChangeRate, t1441)")
    class GetTopPriceChangeRate {

        @Test
        @DisplayName("t1441OutBlock1 배열을 순위(1부터) 매겨 파싱한다")
        void success_parsesRankedRows() {
            when(accessTokenProvider.issueAccessToken()).thenReturn("test-token");
            mockServer.expect(requestTo(HIGH_ITEM_URL))
                    .andRespond(withSuccess("""
                            {"t1441OutBlock1":[
                              {"hname":"롯데쇼핑","shcode":"023530","price":106400,"change":6700,"diff":"6.72","volume":227977}
                            ]}""", MediaType.APPLICATION_JSON));

            List<RankingItemDto> result = client.getTopPriceChangeRate();

            assertThat(result).hasSize(1);
            RankingItemDto item = result.get(0);
            assertThat(item.getRank()).isEqualTo(1);
            assertThat(item.getStockName()).isEqualTo("롯데쇼핑");
            assertThat(item.getStockCode()).isEqualTo("023530");
            assertThat(item.getChangeRate()).isEqualTo(6.72);
            mockServer.verify();
        }

        @Test
        @DisplayName("코스피+코스닥 전체(gubun1=0)·상승률(gubun2=0)·당일(gubun3=0) 조건으로 t1441을 호출한다(#13)")
        void request_usesWholeMarketTodayRisingCondition() {
            when(accessTokenProvider.issueAccessToken()).thenReturn("test-token");
            mockServer.expect(requestTo(HIGH_ITEM_URL))
                    .andExpect(header("tr_cd", "t1441"))
                    .andExpect(jsonPath("$.t1441InBlock.gubun1").value("0"))
                    .andExpect(jsonPath("$.t1441InBlock.gubun2").value("0"))
                    .andExpect(jsonPath("$.t1441InBlock.gubun3").value("0"))
                    .andRespond(withSuccess("{\"t1441OutBlock1\":[]}", MediaType.APPLICATION_JSON));

            client.getTopPriceChangeRate();

            mockServer.verify();
        }

        @Test
        @DisplayName("sign이 하락(5)이면 change가 양수 크기로 와도 changeAmount를 음수로 뒤집는다")
        void success_negativeChangeAmount_whenSignIndicatesDecline() {
            when(accessTokenProvider.issueAccessToken()).thenReturn("test-token");
            mockServer.expect(requestTo(HIGH_ITEM_URL))
                    .andRespond(withSuccess("""
                            {"t1441OutBlock1":[
                              {"hname":"하드웰옵틱스","shcode":"000230","price":6140,"sign":"5","change":"70","diff":"-1.13","volume":7691}
                            ]}""", MediaType.APPLICATION_JSON));

            List<RankingItemDto> result = client.getTopPriceChangeRate();

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getChangeAmount()).isEqualTo(-70L);
            mockServer.verify();
        }

        @Test
        @DisplayName("t1441OutBlock1이 없으면 빈 리스트를 반환한다")
        void empty_whenOutBlockMissing() {
            when(accessTokenProvider.issueAccessToken()).thenReturn("test-token");
            mockServer.expect(requestTo(HIGH_ITEM_URL))
                    .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

            List<RankingItemDto> result = client.getTopPriceChangeRate();

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("결과가 11건을 넘으면 상위 10건까지만 잘라 반환한다")
        void success_limitsToTenItems() {
            when(accessTokenProvider.issueAccessToken()).thenReturn("test-token");
            String rows = "{\"t1441OutBlock1\":["
                    + java.util.stream.IntStream.rangeClosed(1, 11)
                            .mapToObj(i -> "{\"hname\":\"종목%d\",\"shcode\":\"00000%d\"}".formatted(i, i))
                            .collect(java.util.stream.Collectors.joining(","))
                    + "]}";
            mockServer.expect(requestTo(HIGH_ITEM_URL))
                    .andRespond(withSuccess(rows, MediaType.APPLICATION_JSON));

            List<RankingItemDto> result = client.getTopPriceChangeRate();

            assertThat(result).hasSize(10);
        }

        @Test
        @DisplayName("외부 시세 데이터 제공사가 5xx로 실패하면 빈 순위 대신 MARKET_DATA_UNAVAILABLE을 던진다(#05)")
        void fail_throwsMarketDataUnavailable_whenProviderReturnsServerError() {
            when(accessTokenProvider.issueAccessToken()).thenReturn("test-token");
            mockServer.expect(requestTo(HIGH_ITEM_URL)).andRespond(withServerError());

            assertThatThrownBy(() -> client.getTopPriceChangeRate())
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.MARKET_DATA_UNAVAILABLE);
            mockServer.verify();
        }
    }

    @Nested
    @DisplayName("하락률상위 조회 (getTopPriceDeclineRate, t1441)")
    class GetTopPriceDeclineRate {

        @Test
        @DisplayName("코스피+코스닥 전체(gubun1=0)·하락률(gubun2=1)·당일(gubun3=0) 조건으로 t1441을 호출한다(#13)")
        void request_usesWholeMarketTodayFallingCondition() {
            when(accessTokenProvider.issueAccessToken()).thenReturn("test-token");
            mockServer.expect(requestTo(HIGH_ITEM_URL))
                    .andExpect(header("tr_cd", "t1441"))
                    .andExpect(jsonPath("$.t1441InBlock.gubun1").value("0"))
                    .andExpect(jsonPath("$.t1441InBlock.gubun2").value("1"))
                    .andExpect(jsonPath("$.t1441InBlock.gubun3").value("0"))
                    .andRespond(withSuccess("""
                            {"t1441OutBlock1":[
                              {"hname":"하드웰옵틱스","shcode":"000230","price":6140,"sign":"5","change":"70","diff":"-1.13","volume":7691}
                            ]}""", MediaType.APPLICATION_JSON));

            List<RankingItemDto> result = client.getTopPriceDeclineRate();

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getRank()).isEqualTo(1);
            assertThat(result.get(0).getChangeRate()).isEqualTo(-1.13);
            assertThat(result.get(0).getChangeAmount()).isEqualTo(-70L);
            mockServer.verify();
        }
    }

    @Nested
    @DisplayName("거래대금상위 조회 (getTopTradingValue, t1463)")
    class GetTopTradingValue {

        @Test
        @DisplayName("거래대금(value) 필드를 extraInfo에 담아 파싱한다")
        void success_parsesWithTradingValueExtraInfo() {
            when(accessTokenProvider.issueAccessToken()).thenReturn("test-token");
            mockServer.expect(requestTo(HIGH_ITEM_URL))
                    .andRespond(withSuccess("""
                            {"t1463OutBlock1":[
                              {"hname":"SK하이닉스","shcode":"000660","price":1420000,"change":2000,"diff":"-0.14","volume":3295283,"value":4729433}
                            ]}""", MediaType.APPLICATION_JSON));

            List<RankingItemDto> result = client.getTopTradingValue();

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getExtraInfo()).contains("4729433");
            mockServer.verify();
        }
    }

    @Nested
    @DisplayName("순위 조회 mock 분기 (market-data.mode=mock)")
    class MockBranch {

        @Mock
        private LocalMarketDataReader localMarketDataReader;

        private HighItemApiClient mockModeClient;

        @BeforeEach
        void setUpMockMode() {
            mockModeClient = new HighItemApiClient(accessTokenProvider, Optional.of(localMarketDataReader), RestClient.builder());
        }

        private CurrentPriceDetailDto stock(String code, String name, long price, long volume, double changeRate) {
            return CurrentPriceDetailDto.builder()
                    .stockCode(code).stockName(name).currentPrice(price).volume(volume).changeRate(changeRate)
                    .listingShares(1_000L)
                    .build();
        }

        @Test
        @DisplayName("거래량상위는 외부 시세 데이터 API를 호출하지 않고 mock 종목을 거래량 내림차순으로 정렬한다")
        void getTopVolume_sortsByVolumeDescending() {
            when(localMarketDataReader.getAllCurrentPrices()).thenReturn(Map.of(
                    "A", stock("A", "가", 1000, 100, 0.0),
                    "B", stock("B", "나", 1000, 300, 0.0),
                    "C", stock("C", "다", 1000, 200, 0.0)));

            List<RankingItemDto> result = mockModeClient.getTopVolume();

            assertThat(result).extracting(RankingItemDto::getStockCode).containsExactly("B", "C", "A");
            assertThat(result.get(0).getRank()).isEqualTo(1);
            verify(accessTokenProvider, never()).issueAccessToken();
        }

        @Test
        @DisplayName("상승률상위는 mock 전체 종목 중 상승 종목만 등락률 내림차순으로 정렬한다(#13)")
        void getTopPriceChangeRate_onlyRisingSortedDescending() {
            when(localMarketDataReader.getAllCurrentPrices()).thenReturn(Map.of(
                    "A", stock("A", "가", 1000, 100, -1.5),
                    "B", stock("B", "나", 1000, 100, 5.0),
                    "C", stock("C", "다", 1000, 100, 0.0),
                    "D", stock("D", "라", 1000, 100, 2.3)));

            List<RankingItemDto> result = mockModeClient.getTopPriceChangeRate();

            assertThat(result).extracting(RankingItemDto::getStockCode).containsExactly("B", "D");
            assertThat(result).extracting(RankingItemDto::getRank).containsExactly(1, 2);
            verify(accessTokenProvider, never()).issueAccessToken();
        }

        @Test
        @DisplayName("하락률상위는 mock 전체 종목 중 하락 종목만 등락률 오름차순(하락폭 큰 순)으로 정렬한다(#13)")
        void getTopPriceDeclineRate_onlyFallingSortedAscending() {
            when(localMarketDataReader.getAllCurrentPrices()).thenReturn(Map.of(
                    "A", stock("A", "가", 1000, 100, -1.5),
                    "B", stock("B", "나", 1000, 100, 5.0),
                    "C", stock("C", "다", 1000, 100, 0.0),
                    "D", stock("D", "라", 1000, 100, -7.2)));

            List<RankingItemDto> result = mockModeClient.getTopPriceDeclineRate();

            assertThat(result).extracting(RankingItemDto::getStockCode).containsExactly("D", "A");
            assertThat(result).extracting(RankingItemDto::getRank).containsExactly(1, 2);
            verify(accessTokenProvider, never()).issueAccessToken();
        }

        @Test
        @DisplayName("하락 종목이 10개를 넘으면 하락률 상위 10건까지만 반환한다")
        void getTopPriceDeclineRate_limitsToTenItems() {
            Map<String, CurrentPriceDetailDto> all = new java.util.HashMap<>();
            for (int i = 1; i <= 15; i++) {
                all.put("C%d".formatted(i), stock("C%d".formatted(i), "종목%d".formatted(i), 1000, 100, -i));
            }
            when(localMarketDataReader.getAllCurrentPrices()).thenReturn(all);

            List<RankingItemDto> result = mockModeClient.getTopPriceDeclineRate();

            assertThat(result).hasSize(10);
            assertThat(result.get(0).getStockCode()).isEqualTo("C15");
            assertThat(result.get(9).getStockCode()).isEqualTo("C6");
        }

        @Test
        @DisplayName("mock 종목이 10개를 넘으면 상위 10건까지만 반환한다")
        void limitsToTenItems() {
            Map<String, CurrentPriceDetailDto> all = new java.util.HashMap<>();
            for (int i = 1; i <= 15; i++) {
                all.put("C%d".formatted(i), stock("C%d".formatted(i), "종목%d".formatted(i), 1000, i, 0.0));
            }
            when(localMarketDataReader.getAllCurrentPrices()).thenReturn(all);

            List<RankingItemDto> result = mockModeClient.getTopVolume();

            assertThat(result).hasSize(10);
        }

        @Test
        @DisplayName("mockLimit을 넘기면 10건 제한 없이 mock 전체 종목을 순위대로 반환한다(홈 무한 스크롤용)")
        void getTopVolume_withMockLimit_returnsAllItems() {
            Map<String, CurrentPriceDetailDto> all = new java.util.HashMap<>();
            for (int i = 1; i <= 15; i++) {
                all.put("C%d".formatted(i), stock("C%d".formatted(i), "종목%d".formatted(i), 1000, i, 0.0));
            }
            when(localMarketDataReader.getAllCurrentPrices()).thenReturn(all);

            List<RankingItemDto> result = mockModeClient.getTopVolume(Integer.MAX_VALUE);

            assertThat(result).hasSize(15);
            assertThat(result.get(0).getStockCode()).isEqualTo("C15");
            assertThat(result.get(14).getRank()).isEqualTo(15);
            verify(accessTokenProvider, never()).issueAccessToken();
        }

        @Test
        @DisplayName("상승률상위에 mockLimit을 넘겨도 상승 종목만 반환한다")
        void getTopPriceChangeRate_withMockLimit_keepsRisingFilter() {
            Map<String, CurrentPriceDetailDto> all = new java.util.HashMap<>();
            for (int i = 1; i <= 15; i++) {
                all.put("C%d".formatted(i), stock("C%d".formatted(i), "종목%d".formatted(i), 1000, 100, i - 3));
            }
            when(localMarketDataReader.getAllCurrentPrices()).thenReturn(all);

            List<RankingItemDto> result = mockModeClient.getTopPriceChangeRate(Integer.MAX_VALUE);

            assertThat(result).hasSize(12);
            assertThat(result).allSatisfy(item -> assertThat(item.getChangeRate()).isPositive());
        }
    }

    @Test
    @DisplayName("real 모드는 mockLimit을 넘겨도 기존처럼 최대 10건만 반환한다")
    void realMode_ignoresMockLimit() {
        when(accessTokenProvider.issueAccessToken()).thenReturn("test-token");
        StringBuilder rows = new StringBuilder();
        for (int i = 1; i <= 15; i++) {
            if (i > 1) rows.append(',');
            rows.append("{\"hname\":\"종목%d\",\"shcode\":\"%06d\",\"price\":1000,\"change\":0,\"diff\":\"0.00\",\"volume\":%d}".formatted(i, i, i));
        }
        mockServer.expect(requestTo(HIGH_ITEM_URL))
                .andRespond(withSuccess("{\"t1452OutBlock1\":[" + rows + "]}", MediaType.APPLICATION_JSON));

        List<RankingItemDto> result = client.getTopVolume(Integer.MAX_VALUE);

        assertThat(result).hasSize(10);
        mockServer.verify();
    }
}
