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

import com.teamfp.aistock.infra.ls.dto.LsInvestorTypeSummaryDto;
import com.teamfp.aistock.infra.ls.dto.LsMarketInvestorComparisonDto;

@ExtendWith(MockitoExtension.class)
class LsInvestorApiClientTest {

    private static final String INVESTOR_URL = "http://test-ls/stock/investor";

    @Mock
    private LsAccessTokenProvider accessTokenProvider;

    private MockRestServiceServer mockServer;
    private LsInvestorApiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();

        client = new LsInvestorApiClient(accessTokenProvider, builder);
        ReflectionTestUtils.setField(client, "investorUrl", INVESTOR_URL);
    }

    @Nested
    @DisplayName("투자자별종합 조회 (getInvestorTypeSummary, t1601)")
    class GetInvestorTypeSummary {

        @Test
        @DisplayName("t1601OutBlock1을 투자자유형별 순매수로 파싱한다")
        void success_parsesSummary() {
            when(accessTokenProvider.issueAccessToken()).thenReturn("test-token");
            mockServer.expect(requestTo(INVESTOR_URL))
                    .andRespond(withSuccess("""
                            {"t1601OutBlock1":{"svolume_08":-8398,"svolume_17":-1581,"svolume_18":6891}}""",
                            MediaType.APPLICATION_JSON));

            Optional<LsInvestorTypeSummaryDto> result = client.getInvestorTypeSummary();

            assertThat(result).isPresent();
            assertThat(result.get().getIndividualNetBuy()).isEqualTo(-8398L);
            assertThat(result.get().getForeignNetBuy()).isEqualTo(-1581L);
            mockServer.verify();
        }
    }

    @Nested
    @DisplayName("투자자매매종합1 조회 (getMarketComparison, t1615)")
    class GetMarketComparison {

        @Test
        @DisplayName("t1615OutBlock1 배열을 시장별 비교로 파싱한다")
        void success_parsesComparison() {
            when(accessTokenProvider.issueAccessToken()).thenReturn("test-token");
            mockServer.expect(requestTo(INVESTOR_URL))
                    .andRespond(withSuccess("""
                            {"t1615OutBlock1":[
                              {"hname":"코스피","sv_08":-6053,"sv_17":-1581,"sv_18":6891}
                            ]}""", MediaType.APPLICATION_JSON));

            List<LsMarketInvestorComparisonDto> result = client.getMarketComparison();

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getMarketName()).isEqualTo("코스피");
            mockServer.verify();
        }
    }
}
