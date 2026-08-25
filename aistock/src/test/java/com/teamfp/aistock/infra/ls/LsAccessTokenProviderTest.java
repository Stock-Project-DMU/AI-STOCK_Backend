package com.teamfp.aistock.infra.ls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.teamfp.aistock.global.exception.CustomException;

/**
 * LsAccessTokenProvider 단위 테스트. LsMarketDataApiClient/LsInvestorTrendApiClient/
 * LsInvestInfoApiClient 3개 클라이언트가 공유하는 토큰 발급 로직 자체를 검증한다.
 */
class LsAccessTokenProviderTest {

    private static final String TOKEN_URL = "http://test-ls/oauth2/token";

    private MockRestServiceServer mockServer;
    private LsAccessTokenProvider provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();

        provider = new LsAccessTokenProvider(builder);
        ReflectionTestUtils.setField(provider, "tokenUrl", TOKEN_URL);
        ReflectionTestUtils.setField(provider, "appKey", "test-app-key");
        ReflectionTestUtils.setField(provider, "appSecret", "test-app-secret");
    }

    @Test
    @DisplayName("client_credentials 그랜트로 폼 인코딩 요청을 보내고 access_token을 그대로 반환한다")
    void success_issuesAccessToken() {
        mockServer.expect(requestTo(TOKEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("grant_type=client_credentials")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("appkey=test-app-key")))
                .andRespond(withSuccess("""
                        {"access_token":"issued-token-123","token_type":"Bearer","expires_in":86400,"scope":"oob"}""",
                        MediaType.APPLICATION_JSON));

        String token = provider.issueAccessToken();

        assertThat(token).isEqualTo("issued-token-123");
        mockServer.verify();
    }

    @Test
    @DisplayName("응답에 access_token이 없으면 CustomException(EXTERNAL_API_ERROR)을 던진다")
    void failure_missingAccessToken_throwsCustomException() {
        mockServer.expect(requestTo(TOKEN_URL))
                .andRespond(withSuccess("""
                        {"error_code":"IGW00105","error_description":"유효하지 않은 AppSecret입니다."}""",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider.issueAccessToken())
                .isInstanceOf(CustomException.class);
    }
}
