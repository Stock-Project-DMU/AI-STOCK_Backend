package com.teamfp.aistock.infra.marketdata;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class MarketDataAccessTokenProviderTest {
    @Test
    void concurrentRequestsShareTokenAndRefreshAfterExpiry() throws Exception {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var provider = new MarketDataAccessTokenProvider(builder);
        ReflectionTestUtils.setField(provider, "tokenUrl", "http://ls/token");
        ReflectionTestUtils.setField(provider, "appKey", "key");
        ReflectionTestUtils.setField(provider, "appSecret", "secret");
        server.expect(requestTo("http://ls/token")).andRespond(withSuccess(
                "{\"access_token\":\"first\",\"expires_in\":3600}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://ls/token")).andRespond(withSuccess(
                "{\"access_token\":\"second\",\"expires_in\":3600}", MediaType.APPLICATION_JSON));
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            var tasks = java.util.stream.IntStream.range(0, 8)
                    .mapToObj(i -> (java.util.concurrent.Callable<String>) provider::issueAccessToken).toList();
            for (var result : executor.invokeAll(tasks)) assertThat(result.get()).isEqualTo("first");
        }
        ReflectionTestUtils.setField(provider, "refreshAt", 0L);
        assertThat(provider.issueAccessToken()).isEqualTo("second");
        server.verify();
    }

    @Test
    void invalidResponseIsNotCachedAndRepeatedFailureIsThrottled() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var provider = new MarketDataAccessTokenProvider(builder);
        ReflectionTestUtils.setField(provider, "tokenUrl", "http://ls/token");
        ReflectionTestUtils.setField(provider, "appKey", "key");
        ReflectionTestUtils.setField(provider, "appSecret", "secret");
        server.expect(requestTo("http://ls/token")).andRespond(withSuccess(
                "{\"rsp_cd\":\"99999\"}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(provider::issueAccessToken).isInstanceOf(com.teamfp.aistock.global.exception.CustomException.class);
        assertThatThrownBy(provider::issueAccessToken).isInstanceOf(com.teamfp.aistock.global.exception.CustomException.class);
        server.verify();
    }

    @Test
    void tokenIssueFailureThrowsMarketDataUnavailable() {
        // 토큰 발급 실패는 "데이터 없음"이 아니라 제공사 장애로 구분돼야 한다(외부 장애와 빈 목록 구분 처리, #05)
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var provider = new MarketDataAccessTokenProvider(builder);
        ReflectionTestUtils.setField(provider, "tokenUrl", "http://ls/token");
        ReflectionTestUtils.setField(provider, "appKey", "key");
        ReflectionTestUtils.setField(provider, "appSecret", "secret");
        server.expect(requestTo("http://ls/token")).andRespond(
                org.springframework.test.web.client.response.MockRestResponseCreators.withServerError());
        assertThatThrownBy(provider::issueAccessToken)
                .isInstanceOf(com.teamfp.aistock.global.exception.CustomException.class)
                .extracting(e -> ((com.teamfp.aistock.global.exception.CustomException) e).getErrorCode())
                .isEqualTo(com.teamfp.aistock.global.exception.ErrorCode.MARKET_DATA_UNAVAILABLE);
        server.verify();
    }
}
