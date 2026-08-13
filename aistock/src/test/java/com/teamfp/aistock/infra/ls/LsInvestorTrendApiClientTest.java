package com.teamfp.aistock.infra.ls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.teamfp.aistock.infra.ls.dto.LsForeignInstitutionalTrendDto;

@ExtendWith(MockitoExtension.class)
class LsInvestorTrendApiClientTest {

    private static final String FRGR_ITT_URL = "http://test-ls/stock/frgr-itt";
    private static final String STOCK_CODE = "005930";

    @Mock
    private LsAccessTokenProvider accessTokenProvider;

    private MockRestServiceServer mockServer;
    private LsInvestorTrendApiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();

        client = new LsInvestorTrendApiClient(accessTokenProvider, builder);
        ReflectionTestUtils.setField(client, "frgrIttUrl", FRGR_ITT_URL);
    }

    @Test
    @DisplayName("t1716OutBlock 배열을 KRX 기준 순매수/소진율/공매도 필드로 정확히 파싱한다")
    void success_parsesTrendRows() {
        when(accessTokenProvider.issueAccessToken()).thenReturn("test-token");
        mockServer.expect(requestTo(FRGR_ITT_URL))
                .andRespond(withSuccess("""
                        {"t1716OutBlock":[
                          {"date":"20260810","close":71000,"krx_0008":-1000,"krx_0018":300,"krx_0009":700,
                           "pgmvol":5000,"fsc_listing":3000000000,"fsc_sjrate":51.23,"gm_volume":200,"gm_value":14200000}
                        ]}""", MediaType.APPLICATION_JSON));

        List<LsForeignInstitutionalTrendDto> result = client.getRecentTrend(STOCK_CODE);

        assertThat(result).hasSize(1);
        LsForeignInstitutionalTrendDto item = result.get(0);
        assertThat(item.getDate()).isEqualTo("20260810");
        assertThat(item.getClosePrice()).isEqualTo(71000L);
        assertThat(item.getIndividualNetBuyKrx()).isEqualTo(-1000L);
        assertThat(item.getInstitutionNetBuyKrx()).isEqualTo(300L);
        assertThat(item.getForeignNetBuyKrx()).isEqualTo(700L);
        assertThat(item.getProgramTradingVolume()).isEqualTo(5000L);
        assertThat(item.getForeignHoldingShares()).isEqualTo(3000000000L);
        assertThat(item.getForeignExhaustionRate()).isEqualTo(51.23);
        assertThat(item.getShortSellingVolume()).isEqualTo(200L);
        assertThat(item.getShortSellingValue()).isEqualTo(14200000L);
        mockServer.verify();
    }

    @Test
    @DisplayName("응답이 6건을 넘으면 최신 5건까지만 잘라 반환한다")
    void success_limitsToFiveItems() {
        when(accessTokenProvider.issueAccessToken()).thenReturn("test-token");
        String sixRows = "{\"t1716OutBlock\":["
                + java.util.stream.IntStream.rangeClosed(1, 6)
                        .mapToObj(i -> "{\"date\":\"2026081%d\",\"close\":1000}".formatted(i))
                        .collect(java.util.stream.Collectors.joining(","))
                + "]}";
        mockServer.expect(requestTo(FRGR_ITT_URL))
                .andRespond(withSuccess(sixRows, MediaType.APPLICATION_JSON));

        List<LsForeignInstitutionalTrendDto> result = client.getRecentTrend(STOCK_CODE);

        assertThat(result).hasSize(5);
    }

    @Test
    @DisplayName("t1716OutBlock이 없으면 빈 리스트를 반환한다")
    void empty_whenOutBlockMissing() {
        when(accessTokenProvider.issueAccessToken()).thenReturn("test-token");
        mockServer.expect(requestTo(FRGR_ITT_URL))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        List<LsForeignInstitutionalTrendDto> result = client.getRecentTrend(STOCK_CODE);

        assertThat(result).isEmpty();
    }
}
