package com.teamfp.aistock.infra.ls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
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

import com.teamfp.aistock.infra.ls.dto.LsCurrentPriceDetailDto;
import com.teamfp.aistock.infra.ls.dto.LsEtfConstituentDto;

@ExtendWith(MockitoExtension.class)
class LsEtfApiClientTest {

    private static final String ETF_URL = "http://test-ls/stock/etf";
    private static final String STOCK_CODE = "069500";

    @Mock
    private LsAccessTokenProvider accessTokenProvider;

    private MockRestServiceServer mockServer;
    private LsEtfApiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();

        client = new LsEtfApiClient(accessTokenProvider, builder);
        ReflectionTestUtils.setField(client, "etfUrl", ETF_URL);
    }

    @Nested
    @DisplayName("ETF현재가 조회 (getCurrentPrice, t1901)")
    class GetCurrentPrice {

        @Test
        @DisplayName("t1901OutBlock을 현재가로 파싱한다")
        void success_parsesPrice() {
            when(accessTokenProvider.issueAccessToken()).thenReturn("test-token");
            mockServer.expect(requestTo(ETF_URL))
                    .andRespond(withSuccess("""
                            {"t1901OutBlock":{"hname":"KODEX 200","price":98265,"change":175,"diff":"0.18","volume":13128894}}""",
                            MediaType.APPLICATION_JSON));

            Optional<LsCurrentPriceDetailDto> result = client.getCurrentPrice(STOCK_CODE);

            assertThat(result).isPresent();
            assertThat(result.get().getStockName()).isEqualTo("KODEX 200");
            assertThat(result.get().getCurrentPrice()).isEqualTo(98265L);
            mockServer.verify();
        }

        @Test
        @DisplayName("per/52주 최고저 날짜/상장주식수/외국인 소진율까지 함께 파싱한다(2026-09 코드리뷰 반영 — 기존엔 DTO에 자리는 있는데 안 채우고 있었음)")
        void success_parsesExtendedFields() {
            when(accessTokenProvider.issueAccessToken()).thenReturn("test-token");
            mockServer.expect(requestTo(ETF_URL))
                    .andRespond(withSuccess("""
                            {"t1901OutBlock":{"hname":"KODEX 200","price":98265,"change":175,"diff":"0.18",
                            "volume":13128894,"per":"15.2","high52w":102000,"high52wdate":"20260301",
                            "low52w":85000,"low52wdate":"20260110","listing":1234567,"exhratio":"12.3"}}""",
                            MediaType.APPLICATION_JSON));

            Optional<LsCurrentPriceDetailDto> result = client.getCurrentPrice(STOCK_CODE);

            assertThat(result).isPresent();
            LsCurrentPriceDetailDto dto = result.get();
            assertThat(dto.getPer()).isEqualTo(15.2);
            assertThat(dto.getHigh52w()).isEqualTo(102000L);
            assertThat(dto.getHigh52wDate()).isEqualTo("20260301");
            assertThat(dto.getLow52w()).isEqualTo(85000L);
            assertThat(dto.getLow52wDate()).isEqualTo("20260110");
            assertThat(dto.getListingShares()).isEqualTo(1234567L);
            assertThat(dto.getForeignExhaustionRate()).isEqualTo(12.3);
            assertThat(dto.getPbr()).isNull(); // ETF(t1901)에는 PBR 대응 필드가 없음
        }

        @Test
        @DisplayName("t1901OutBlock이 없으면 빈 값을 반환한다")
        void empty_whenOutBlockMissing() {
            when(accessTokenProvider.issueAccessToken()).thenReturn("test-token");
            mockServer.expect(requestTo(ETF_URL)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

            Optional<LsCurrentPriceDetailDto> result = client.getCurrentPrice(STOCK_CODE);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("ETF구성종목 조회 (getConstituents, t1904)")
    class GetConstituents {

        @Test
        @DisplayName("t1904OutBlock1 배열을 구성종목으로 파싱한다")
        void success_parsesConstituents() {
            when(accessTokenProvider.issueAccessToken()).thenReturn("test-token");
            mockServer.expect(requestTo(ETF_URL))
                    .andRespond(withSuccess("""
                            {"t1904OutBlock1":[
                              {"shcode":"005930","hname":"삼성전자","price":230000,"diff":"1.2","weight":25.5}
                            ]}""", MediaType.APPLICATION_JSON));

            List<LsEtfConstituentDto> result = client.getConstituents(STOCK_CODE);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getStockName()).isEqualTo("삼성전자");
            assertThat(result.get(0).getWeight()).isEqualTo(25.5);
            mockServer.verify();
        }
    }
}
