package com.teamfp.aistock.infra.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

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

import com.teamfp.aistock.infra.marketdata.dto.CurrentPriceDetailDto;
import com.teamfp.aistock.infra.marketdata.dto.ExpectedIndexDto;
import com.teamfp.aistock.infra.marketdata.dto.IndustryPriceDto;

@ExtendWith(MockitoExtension.class)
class IndustryApiClientTest {

    private static final String INDUSTRY_URL = "http://test-ls/indtp/market-data";

    @Mock
    private MarketDataAccessTokenProvider accessTokenProvider;

    private MockRestServiceServer mockServer;
    private IndustryApiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();

        client = new IndustryApiClient(accessTokenProvider, java.util.Optional.empty(), builder);
        ReflectionTestUtils.setField(client, "industryUrl", INDUSTRY_URL);
    }

    @Nested
    @DisplayName("업종현재가 조회 (getCurrentPrice, t1511)")
    class GetCurrentPrice {

        @Test
        @DisplayName("t1511OutBlock을 업종지수 현재가로 파싱한다")
        void success_parsesPrice() {
            when(accessTokenProvider.issueAccessToken()).thenReturn("test-token");
            mockServer.expect(requestTo(INDUSTRY_URL))
                    .andRespond(withSuccess("""
                            {"t1511OutBlock":{"hname":"종합","pricejisu":"2617.58","diffjisu":"0.65"}}""",
                            MediaType.APPLICATION_JSON));

            Optional<IndustryPriceDto> result = client.getCurrentPrice("코스피");

            assertThat(result).isPresent();
            assertThat(result.get().getIndexValue()).isEqualTo(2617.58);
            mockServer.verify();
        }
    }

    @Nested
    @DisplayName("예상지수 조회 (getExpectedIndex, t1485)")
    class GetExpectedIndex {

        @Test
        @DisplayName("t1485OutBlock을 예상지수로 파싱한다")
        void success_parsesExpectedIndex() {
            when(accessTokenProvider.issueAccessToken()).thenReturn("test-token");
            mockServer.expect(requestTo(INDUSTRY_URL))
                    .andRespond(withSuccess("""
                            {"t1485OutBlock":{"pricejisu":"2610.62","change":"9.26","yupjo":0,"ydownjo":0}}""",
                            MediaType.APPLICATION_JSON));

            Optional<ExpectedIndexDto> result = client.getExpectedIndex("코스피", "장전");

            assertThat(result).isPresent();
            assertThat(result.get().getExpectedIndexValue()).isEqualTo(2610.62);
            mockServer.verify();
        }

        @Test
        @DisplayName("sign이 하락(5)이면 change가 양수 크기로 와도 changeRate를 음수로 뒤집는다")
        void success_negativeChangeRate_whenSignIndicatesDecline() {
            when(accessTokenProvider.issueAccessToken()).thenReturn("test-token");
            mockServer.expect(requestTo(INDUSTRY_URL))
                    .andRespond(withSuccess("""
                            {"t1485OutBlock":{"pricejisu":"6880.99","sign":"5","change":"152.93","yupjo":2,"ydownjo":0}}""",
                            MediaType.APPLICATION_JSON));

            Optional<ExpectedIndexDto> result = client.getExpectedIndex("코스피", "장전");

            assertThat(result).isPresent();
            assertThat(result.get().getChangeRate()).isEqualTo(-152.93);
            mockServer.verify();
        }
    }

    @Nested
    @DisplayName("업종현재가 조회 mock 분기 (getCurrentPrice, market-data.mode=mock)")
    class GetCurrentPriceMockBranch {

        @Mock
        private LocalMarketDataReader localMarketDataReader;

        private IndustryApiClient mockModeClient;

        @BeforeEach
        void setUpMockMode() {
            mockModeClient = new IndustryApiClient(accessTokenProvider, Optional.of(localMarketDataReader), RestClient.builder());
        }

        private CurrentPriceDetailDto stock(String market, double changeRate) {
            return CurrentPriceDetailDto.builder().stockCode("X").stockName("종목").currentPrice(1000L)
                    .market(market).changeRate(changeRate).build();
        }

        @Test
        @DisplayName("외부 시세 데이터 API를 호출하지 않고 같은 시장(KOSPI) mock 종목의 평균 등락률로 지수를 근사한다")
        void usesLocalReader_averagesSameMarketChangeRate() {
            when(localMarketDataReader.getAllCurrentPrices()).thenReturn(Map.of(
                    "A", stock("KOSPI", 2.0),
                    "B", stock("KOSPI", 4.0),
                    "C", stock("KOSDAQ", 100.0)));

            Optional<IndustryPriceDto> result = mockModeClient.getCurrentPrice("코스피");

            assertThat(result).isPresent();
            assertThat(result.get().getChangeRate()).isEqualTo(3.0);
            verify(accessTokenProvider, never()).issueAccessToken();
        }

        @Test
        @DisplayName("해당 시장에 mock 종목이 하나도 없으면 빈 값을 반환한다")
        void empty_whenNoMockStockInMarket() {
            when(localMarketDataReader.getAllCurrentPrices()).thenReturn(Map.of(
                    "A", stock("KOSDAQ", 1.0)));

            Optional<IndustryPriceDto> result = mockModeClient.getCurrentPrice("코스피");

            assertThat(result).isEmpty();
        }
    }
}
