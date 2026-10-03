package com.teamfp.aistock.domain.stock.controller;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;

import com.teamfp.aistock.domain.stock.service.MarketQueryService;
import com.teamfp.aistock.global.exception.ErrorCode;
import com.teamfp.aistock.global.exception.GlobalExceptionHandler;
import com.teamfp.aistock.infra.marketdata.HighItemApiClient;
import com.teamfp.aistock.infra.marketdata.MarketDataAccessTokenProvider;

/**
 * 외부 장애와 빈 목록 구분 처리(#05)를 HTTP 응답 수준에서 확인한다 — 외부 시세 데이터 제공사 응답만
 * MockRestServiceServer로 흉내 내고, 토큰 발급(MarketDataAccessTokenProvider) → 순위 조회
 * (HighItemApiClient) → MarketQueryService → MarketQueryController → GlobalExceptionHandler까지는
 * 실제 클래스를 그대로 거친다. 제공사 장애는 503(MARKET_DATA_UNAVAILABLE), 정상 응답의 빈 목록은
 * 200 + 빈 배열로 구분되는지 본다.
 */
class MarketQueryControllerTest {

    private static final String TOKEN_URL = "http://test-ls/oauth2/token";
    private static final String HIGH_ITEM_URL = "http://test-ls/stock/high-item";
    private static final String RANKINGS_PATH = "/api/market/rankings";

    private MockRestServiceServer providerServer;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // 토큰 발급과 순위 조회가 같은 가짜 제공사 서버를 쓰도록 빌더 하나를 공유한다.
        RestClient.Builder builder = RestClient.builder();
        providerServer = MockRestServiceServer.bindTo(builder).build();

        MarketDataAccessTokenProvider tokenProvider = new MarketDataAccessTokenProvider(builder);
        ReflectionTestUtils.setField(tokenProvider, "tokenUrl", TOKEN_URL);
        ReflectionTestUtils.setField(tokenProvider, "appKey", "test-key");
        ReflectionTestUtils.setField(tokenProvider, "appSecret", "test-secret");

        // 등록 종목 전체 순위(all=true) 경로를 쓰지 않으므로 RegisteredStockReader·MarketDataApiClient는 null로 둔다.
        HighItemApiClient highItemApiClient = new HighItemApiClient(tokenProvider, Optional.empty(), null, null, builder);
        ReflectionTestUtils.setField(highItemApiClient, "highItemUrl", HIGH_ITEM_URL);

        // 순위 조회 경로에서 쓰지 않는 클라이언트(현재가·뉴스·업종·투자정보·DART)는 null로 둔다.
        MarketQueryService marketQueryService =
                new MarketQueryService(highItemApiClient, null, null, null, null, null);
        ReflectionTestUtils.setField(marketQueryService, "appKey", "test-key");
        ReflectionTestUtils.setField(marketQueryService, "appSecret", "test-secret");

        mockMvc = MockMvcBuilders.standaloneSetup(new MarketQueryController(marketQueryService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("순위 조회 TR이 HTTP 500으로 실패하면 빈 목록 대신 503 MARKET_DATA_UNAVAILABLE을 반환한다")
    void rankings_returns503_whenProviderReturnsServerError() throws Exception {
        providerServer.expect(requestTo(TOKEN_URL)).andRespond(withSuccess(
                "{\"access_token\":\"test-token\",\"expires_in\":3600}", MediaType.APPLICATION_JSON));
        providerServer.expect(requestTo(HIGH_ITEM_URL)).andRespond(withServerError());

        mockMvc.perform(get(RANKINGS_PATH).param("sort", "market-cap"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(ErrorCode.MARKET_DATA_UNAVAILABLE.getMessage()));
        providerServer.verify();
    }

    @Test
    @DisplayName("토큰 발급이 HTTP 500으로 실패하면 503 MARKET_DATA_UNAVAILABLE을 반환하고 순위 TR은 호출하지 않는다")
    void rankings_returns503_whenTokenIssueFails() throws Exception {
        providerServer.expect(requestTo(TOKEN_URL)).andRespond(withServerError());

        mockMvc.perform(get(RANKINGS_PATH).param("sort", "market-cap"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(ErrorCode.MARKET_DATA_UNAVAILABLE.getMessage()));
        providerServer.verify();
    }

    @Test
    @DisplayName("제공사가 정상 응답했지만 순위 데이터가 비어 있으면 200 + 빈 목록을 반환한다")
    void rankings_returns200WithEmptyList_whenProviderRespondsWithNoData() throws Exception {
        providerServer.expect(requestTo(TOKEN_URL)).andRespond(withSuccess(
                "{\"access_token\":\"test-token\",\"expires_in\":3600}", MediaType.APPLICATION_JSON));
        providerServer.expect(requestTo(HIGH_ITEM_URL)).andRespond(withSuccess(
                "{\"rsp_cd\":\"00000\",\"t1444OutBlock1\":[]}", MediaType.APPLICATION_JSON));

        mockMvc.perform(get(RANKINGS_PATH).param("sort", "market-cap"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
        providerServer.verify();
    }
}
