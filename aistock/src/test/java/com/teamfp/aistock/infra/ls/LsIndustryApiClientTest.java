package com.teamfp.aistock.infra.ls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

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

import com.teamfp.aistock.infra.ls.dto.LsExpectedIndexDto;
import com.teamfp.aistock.infra.ls.dto.LsIndustryPriceDto;

@ExtendWith(MockitoExtension.class)
class LsIndustryApiClientTest {

    private static final String INDUSTRY_URL = "http://test-ls/indtp/market-data";

    @Mock
    private LsAccessTokenProvider accessTokenProvider;

    private MockRestServiceServer mockServer;
    private LsIndustryApiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();

        client = new LsIndustryApiClient(accessTokenProvider, builder);
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

            Optional<LsIndustryPriceDto> result = client.getCurrentPrice("코스피");

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

            Optional<LsExpectedIndexDto> result = client.getExpectedIndex("코스피", "장전");

            assertThat(result).isPresent();
            assertThat(result.get().getExpectedIndexValue()).isEqualTo(2610.62);
            mockServer.verify();
        }
    }
}
