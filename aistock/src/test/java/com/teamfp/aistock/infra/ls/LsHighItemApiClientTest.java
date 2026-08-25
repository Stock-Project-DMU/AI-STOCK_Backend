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

import com.teamfp.aistock.infra.ls.dto.LsRankingItemDto;

@ExtendWith(MockitoExtension.class)
class LsHighItemApiClientTest {

    private static final String HIGH_ITEM_URL = "http://test-ls/stock/high-item";

    @Mock
    private LsAccessTokenProvider accessTokenProvider;

    private MockRestServiceServer mockServer;
    private LsHighItemApiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();

        client = new LsHighItemApiClient(accessTokenProvider, builder);
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

            List<LsRankingItemDto> result = client.getTopPriceChangeRate();

            assertThat(result).hasSize(1);
            LsRankingItemDto item = result.get(0);
            assertThat(item.getRank()).isEqualTo(1);
            assertThat(item.getStockName()).isEqualTo("롯데쇼핑");
            assertThat(item.getStockCode()).isEqualTo("023530");
            assertThat(item.getChangeRate()).isEqualTo(6.72);
            mockServer.verify();
        }

        @Test
        @DisplayName("t1441OutBlock1이 없으면 빈 리스트를 반환한다")
        void empty_whenOutBlockMissing() {
            when(accessTokenProvider.issueAccessToken()).thenReturn("test-token");
            mockServer.expect(requestTo(HIGH_ITEM_URL))
                    .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

            List<LsRankingItemDto> result = client.getTopPriceChangeRate();

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

            List<LsRankingItemDto> result = client.getTopPriceChangeRate();

            assertThat(result).hasSize(10);
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

            List<LsRankingItemDto> result = client.getTopTradingValue();

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getExtraInfo()).contains("4729433");
            mockServer.verify();
        }
    }
}
