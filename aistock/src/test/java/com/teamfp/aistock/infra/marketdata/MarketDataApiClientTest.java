package com.teamfp.aistock.infra.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

import com.teamfp.aistock.infra.marketdata.dto.CurrentPriceDetailDto;
import com.teamfp.aistock.infra.marketdata.dto.MultiStockPriceDto;
import com.teamfp.aistock.infra.marketdata.dto.PivotLevelDto;
import com.teamfp.aistock.infra.marketdata.dto.StockRiskFlagDto;

/**
 * MarketDataApiClient 단위 테스트. MarketDataAccessTokenProvider는 별도 컴포넌트로 분리되어 있어
 * (2026-08-10) DartApiClientTest처럼 토큰 발급 HTTP 호출까지 흉내낼 필요 없이 Mockito로 바로
 * 목킹한다 — 이게 이 리팩터링의 부수 효과 중 하나다(토큰 발급 로직이 테스트 대상 클라이언트
 * 내부에 감춰져 있지 않고 주입 가능한 의존성이 됨).
 */
@ExtendWith(MockitoExtension.class)
class MarketDataApiClientTest {

    private static final String MARKET_DATA_URL = "http://test-ls/stock/market-data";
    private static final String STOCK_CODE = "005930";

    @Mock
    private MarketDataAccessTokenProvider accessTokenProvider;

    private MockRestServiceServer mockServer;
    private MarketDataApiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();

        client = new MarketDataApiClient(accessTokenProvider, Optional.empty(), builder);
        ReflectionTestUtils.setField(client, "marketDataUrl", MARKET_DATA_URL);
    }

    @Test
    @DisplayName("t1102 응답에서 현재가 기본 필드와 PER/PBR/52주 고저/상장주식수/소진율까지 모두 파싱한다")
    void success_parsesBasicAndExtendedFields() {
        when(accessTokenProvider.issueAccessToken()).thenReturn("test-token");
        mockServer.expect(requestTo(MARKET_DATA_URL))
                .andRespond(withSuccess("""
                        {"t1102OutBlock":{
                          "hname":"삼성전자","price":"71000","sign":"2","change":"500","diff":"0.71","volume":"12345678",
                          "per":"12.34","pbrx":"1.56","high52w":"88800","high52wdate":"20250701",
                          "low52w":"49900","low52wdate":"20250115","listing":"5969783","exhratio":"51.23"
                        }}""", MediaType.APPLICATION_JSON));

        Optional<CurrentPriceDetailDto> result = client.getCurrentPrice(STOCK_CODE);

        assertThat(result).isPresent();
        CurrentPriceDetailDto price = result.get();
        assertThat(price.getStockName()).isEqualTo("삼성전자");
        assertThat(price.getCurrentPrice()).isEqualTo(71000L);
        assertThat(price.getChangeAmount()).isEqualTo(500L);
        assertThat(price.getChangeRate()).isEqualTo(0.71);
        assertThat(price.getVolume()).isEqualTo(12345678L);
        assertThat(price.getPer()).isEqualTo(12.34);
        assertThat(price.getPbr()).isEqualTo(1.56);
        assertThat(price.getHigh52w()).isEqualTo(88800L);
        assertThat(price.getHigh52wDate()).isEqualTo("20250701");
        assertThat(price.getLow52w()).isEqualTo(49900L);
        assertThat(price.getLow52wDate()).isEqualTo("20250115");
        assertThat(price.getListingShares()).isEqualTo(5969783L);
        assertThat(price.getForeignExhaustionRate()).isEqualTo(51.23);
        mockServer.verify();
    }

    @Test
    @DisplayName("sign이 하락(5)이면 change가 양수 크기로 와도 changeAmount를 음수로 뒤집는다")
    void success_negativeChangeAmount_whenSignIndicatesDecline() {
        when(accessTokenProvider.issueAccessToken()).thenReturn("test-token");
        mockServer.expect(requestTo(MARKET_DATA_URL))
                .andRespond(withSuccess("""
                        {"t1102OutBlock":{
                          "hname":"삼성전자","price":"259500","sign":"5","change":"9500","diff":"-3.53","volume":"9966543"
                        }}""", MediaType.APPLICATION_JSON));

        Optional<CurrentPriceDetailDto> result = client.getCurrentPrice(STOCK_CODE);

        assertThat(result).isPresent();
        assertThat(result.get().getChangeAmount()).isEqualTo(-9500L);
        assertThat(result.get().getChangeRate()).isEqualTo(-3.53);
        mockServer.verify();
    }

    @Test
    @DisplayName("sign이 하한(4)이어도 changeAmount를 음수로 뒤집는다")
    void success_negativeChangeAmount_whenSignIndicatesLowerLimit() {
        when(accessTokenProvider.issueAccessToken()).thenReturn("test-token");
        mockServer.expect(requestTo(MARKET_DATA_URL))
                .andRespond(withSuccess("""
                        {"t1102OutBlock":{
                          "hname":"삼성전자","price":"50000","sign":"4","change":"7500","diff":"-13.04","volume":"1000000"
                        }}""", MediaType.APPLICATION_JSON));

        Optional<CurrentPriceDetailDto> result = client.getCurrentPrice(STOCK_CODE);

        assertThat(result).isPresent();
        assertThat(result.get().getChangeAmount()).isEqualTo(-7500L);
        mockServer.verify();
    }

    @Test
    @DisplayName("PER/PBR/소진율 필드가 응답에 없으면 0으로 뭉개지 않고 null로 남긴다")
    void success_missingRatioFields_areNullNotZero() {
        when(accessTokenProvider.issueAccessToken()).thenReturn("test-token");
        mockServer.expect(requestTo(MARKET_DATA_URL))
                .andRespond(withSuccess("""
                        {"t1102OutBlock":{"hname":"우선주종목","price":"10000","change":"0","diff":"0","volume":"100"}}""",
                        MediaType.APPLICATION_JSON));

        Optional<CurrentPriceDetailDto> result = client.getCurrentPrice(STOCK_CODE);

        assertThat(result).isPresent();
        assertThat(result.get().getPer()).isNull();
        assertThat(result.get().getPbr()).isNull();
        assertThat(result.get().getForeignExhaustionRate()).isNull();
    }

    @Test
    @DisplayName("응답에 price 필드 자체가 없으면 빈 값을 반환한다")
    void empty_whenPriceFieldMissing() {
        when(accessTokenProvider.issueAccessToken()).thenReturn("test-token");
        mockServer.expect(requestTo(MARKET_DATA_URL))
                .andRespond(withSuccess("""
                        {"t1102OutBlock":{"hname":"삼성전자"}}""", MediaType.APPLICATION_JSON));

        Optional<CurrentPriceDetailDto> result = client.getCurrentPrice(STOCK_CODE);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("t1102OutBlock 자체가 없으면 빈 값을 반환한다")
    void empty_whenOutBlockMissing() {
        when(accessTokenProvider.issueAccessToken()).thenReturn("test-token");
        mockServer.expect(requestTo(MARKET_DATA_URL))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        Optional<CurrentPriceDetailDto> result = client.getCurrentPrice(STOCK_CODE);

        assertThat(result).isEmpty();
    }

    @Nested
    @DisplayName("현재가 조회 mock 분기 (getCurrentPrice, market-data.mode=mock)")
    class GetCurrentPriceMockBranch {

        @Mock
        private LocalMarketDataReader localMarketDataReader;

        @Test
        @DisplayName("localMarketDataReader가 존재하면 외부 시세 데이터 API를 호출하지 않고 로컬 파일 결과를 그대로 반환한다")
        void usesLocalReader_whenPresent_andSkipsRealApiCall() {
            CurrentPriceDetailDto localResult = CurrentPriceDetailDto.builder()
                    .stockCode(STOCK_CODE)
                    .stockName("삼성전자(로컬)")
                    .currentPrice(70000L)
                    .build();
            when(localMarketDataReader.getCurrentPrice(STOCK_CODE)).thenReturn(Optional.of(localResult));
            MarketDataApiClient mockModeClient =
                    new MarketDataApiClient(accessTokenProvider, Optional.of(localMarketDataReader), RestClient.builder());
            ReflectionTestUtils.setField(mockModeClient, "marketDataUrl", MARKET_DATA_URL);

            Optional<CurrentPriceDetailDto> result = mockModeClient.getCurrentPrice(STOCK_CODE);

            assertThat(result).isPresent();
            assertThat(result.get().getStockName()).isEqualTo("삼성전자(로컬)");
            verify(accessTokenProvider, never()).issueAccessToken();
        }

        @Test
        @DisplayName("localMarketDataReader가 빈 값을 반환하면 실제 외부 시세 데이터 API로 폴백하지 않고 그대로 빈 값을 반환한다")
        void empty_whenLocalReaderEmpty_noFallbackToRealApi() {
            when(localMarketDataReader.getCurrentPrice(STOCK_CODE)).thenReturn(Optional.empty());
            MarketDataApiClient mockModeClient =
                    new MarketDataApiClient(accessTokenProvider, Optional.of(localMarketDataReader), RestClient.builder());
            ReflectionTestUtils.setField(mockModeClient, "marketDataUrl", MARKET_DATA_URL);

            Optional<CurrentPriceDetailDto> result = mockModeClient.getCurrentPrice(STOCK_CODE);

            assertThat(result).isEmpty();
            verify(accessTokenProvider, never()).issueAccessToken();
        }
    }

    @Nested
    @DisplayName("피봇/디마크 조회 (getPivotLevels, t1105)")
    class GetPivotLevels {

        @Test
        @DisplayName("t1105OutBlock을 지지·저항선으로 파싱한다")
        void success_parsesPivotLevels() {
            when(accessTokenProvider.issueAccessToken()).thenReturn("test-token");
            mockServer.expect(requestTo(MARKET_DATA_URL))
                    .andRespond(withSuccess("""
                            {"t1105OutBlock":{"shcode":"005930","pbot":70000,"offer1":72000,"supp1":68000,"offer2":74000,"supp2":66000}}""",
                            MediaType.APPLICATION_JSON));

            Optional<PivotLevelDto> result = client.getPivotLevels(STOCK_CODE);

            assertThat(result).isPresent();
            assertThat(result.get().getPivot()).isEqualTo(70000L);
            assertThat(result.get().getResistance1()).isEqualTo(72000L);
            mockServer.verify();
        }
    }

    @Nested
    @DisplayName("위험신호 조회 (getRiskFlags, t1404+t1405)")
    class GetRiskFlags {

        @Test
        @DisplayName("관리종목(t1404)만 해당 종목코드로 잡히면 1건만 반환한다")
        void success_filtersByStockCodeAcrossBothTrs() {
            when(accessTokenProvider.issueAccessToken()).thenReturn("test-token");
            mockServer.expect(requestTo(MARKET_DATA_URL))
                    .andRespond(withSuccess("""
                            {"t1404OutBlock1":[{"shcode":"005930","reason":"5102","date":"20260101"}]}""",
                            MediaType.APPLICATION_JSON));
            mockServer.expect(requestTo(MARKET_DATA_URL))
                    .andRespond(withSuccess("""
                            {"t1405OutBlock1":[{"shcode":"000660","reason":"9999","date":"20260101"}]}""",
                            MediaType.APPLICATION_JSON));

            List<StockRiskFlagDto> result = client.getRiskFlags(STOCK_CODE);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getFlagType()).isEqualTo("관리종목");
            mockServer.verify();
        }
    }

    @Nested
    @DisplayName("멀티종목현재가 조회 (getMultiStockPrices, t8407)")
    class GetMultiStockPrices {

        @Test
        @DisplayName("t8407OutBlock1 배열을 여러 종목 현재가로 파싱한다")
        void success_parsesMultipleStocks() {
            when(accessTokenProvider.issueAccessToken()).thenReturn("test-token");
            mockServer.expect(requestTo(MARKET_DATA_URL))
                    .andRespond(withSuccess("""
                            {"t8407OutBlock1":[
                              {"shcode":"005930","hname":"삼성전자","price":230000,"change":1000,"diff":"0.44","volume":16327805}
                            ]}""", MediaType.APPLICATION_JSON));

            List<MultiStockPriceDto> result = client.getMultiStockPrices(List.of("005930"));

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getStockName()).isEqualTo("삼성전자");
            mockServer.verify();
        }

        @Test
        @DisplayName("종목코드 목록이 비어있으면 호출 없이 빈 리스트를 반환한다")
        void empty_whenStockCodesEmpty() {
            List<MultiStockPriceDto> result = client.getMultiStockPrices(List.of());

            assertThat(result).isEmpty();
        }
    }
}
