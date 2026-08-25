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

import com.teamfp.aistock.infra.ls.dto.LsProgramTradingRankDto;
import com.teamfp.aistock.infra.ls.dto.LsProgramTradingSnapshotDto;

@ExtendWith(MockitoExtension.class)
class LsProgramApiClientTest {

    private static final String PROGRAM_URL = "http://test-ls/stock/program";

    @Mock
    private LsAccessTokenProvider accessTokenProvider;

    private MockRestServiceServer mockServer;
    private LsProgramApiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();

        client = new LsProgramApiClient(accessTokenProvider, builder);
        ReflectionTestUtils.setField(client, "programUrl", PROGRAM_URL);
    }

    @Nested
    @DisplayName("종목별프로그램매매동향 조회 (getTopProgramTradingStocks, t1636)")
    class GetTopProgramTradingStocks {

        @Test
        @DisplayName("t1636OutBlock1 배열을 순매수 상위 랭킹으로 파싱한다")
        void success_parsesRankRows() {
            when(accessTokenProvider.issueAccessToken()).thenReturn("test-token");
            mockServer.expect(requestTo(PROGRAM_URL))
                    .andRespond(withSuccess("""
                            {"t1636OutBlock1":[
                              {"rank":1,"hname":"삼성전자","shcode":"005930","price":230000,"diff":"0.5","svalue":964486163}
                            ]}""", MediaType.APPLICATION_JSON));

            List<LsProgramTradingRankDto> result = client.getTopProgramTradingStocks();

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getStockName()).isEqualTo("삼성전자");
            assertThat(result.get(0).getNetBuyValue()).isEqualTo(964486163L);
            mockServer.verify();
        }
    }

    @Nested
    @DisplayName("프로그램매매종합미니 조회 (getMarketSnapshot, t1640)")
    class GetMarketSnapshot {

        @Test
        @DisplayName("t1640OutBlock을 시장 전체 스냅샷으로 파싱한다")
        void success_parsesSnapshot() {
            when(accessTokenProvider.issueAccessToken()).thenReturn("test-token");
            mockServer.expect(requestTo(PROGRAM_URL))
                    .andRespond(withSuccess("""
                            {"t1640OutBlock":{"offervalue":"7657799","bidvalue":"6291715","value":"-1366084"}}""",
                            MediaType.APPLICATION_JSON));

            Optional<LsProgramTradingSnapshotDto> result = client.getMarketSnapshot();

            assertThat(result).isPresent();
            assertThat(result.get().getNetValue()).isEqualTo(-1366084L);
            mockServer.verify();
        }
    }
}
