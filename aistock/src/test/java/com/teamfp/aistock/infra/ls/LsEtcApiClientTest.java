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

import com.teamfp.aistock.infra.ls.dto.LsNewListingDto;
import com.teamfp.aistock.infra.ls.dto.LsStockCreditInfoDto;
import com.teamfp.aistock.infra.ls.dto.LsStockMasterInfoDto;

@ExtendWith(MockitoExtension.class)
class LsEtcApiClientTest {

    private static final String ETC_URL = "http://test-ls/stock/etc";
    private static final String STOCK_CODE = "005930";

    @Mock
    private LsAccessTokenProvider accessTokenProvider;

    private MockRestServiceServer mockServer;
    private LsEtcApiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();

        client = new LsEtcApiClient(accessTokenProvider, builder);
        ReflectionTestUtils.setField(client, "etcUrl", ETC_URL);
    }

    @Nested
    @DisplayName("담보대출가능여부 조회 (getCollateralLoanEligibility, CLNAQ00100)")
    class GetCollateralLoanEligibility {

        @Test
        @DisplayName("CLNAQ00100OutBlock2 첫 행의 RegTpNm을 담보대출 가능 여부 문장으로 만든다")
        void success_parsesEligibility() {
            when(accessTokenProvider.issueAccessToken()).thenReturn("test-token");
            mockServer.expect(requestTo(ETC_URL))
                    .andRespond(withSuccess("""
                            {"CLNAQ00100OutBlock2":[{"IsuNo":"A005930","RegTpNm":"가능"}]}""",
                            MediaType.APPLICATION_JSON));

            Optional<LsStockCreditInfoDto> result = client.getCollateralLoanEligibility(STOCK_CODE);

            assertThat(result).isPresent();
            assertThat(result.get().getDetail()).contains("가능");
            mockServer.verify();
        }

        @Test
        @DisplayName("결과 행이 없으면 빈 값을 반환한다")
        void empty_whenNoRows() {
            when(accessTokenProvider.issueAccessToken()).thenReturn("test-token");
            mockServer.expect(requestTo(ETC_URL))
                    .andRespond(withSuccess("{\"CLNAQ00100OutBlock2\":[]}", MediaType.APPLICATION_JSON));

            Optional<LsStockCreditInfoDto> result = client.getCollateralLoanEligibility(STOCK_CODE);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("신규상장종목 조회 (getNewListings, t1403)")
    class GetNewListings {

        @Test
        @DisplayName("t1403OutBlock1 배열을 신규상장 목록으로 파싱한다")
        void success_parsesListings() {
            when(accessTokenProvider.issueAccessToken()).thenReturn("test-token");
            mockServer.expect(requestTo(ETC_URL))
                    .andRespond(withSuccess("""
                            {"t1403OutBlock1":[
                              {"hname":"신규상장A","shcode":"999999","date":"20260801","price":10000}
                            ]}""", MediaType.APPLICATION_JSON));

            List<LsNewListingDto> result = client.getNewListings();

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getStockName()).isEqualTo("신규상장A");
            mockServer.verify();
        }
    }

    @Nested
    @DisplayName("종목마스터 조회 (getStockMasterInfo, t8436)")
    class GetStockMasterInfo {

        @Test
        @DisplayName("shcode가 일치하는 행만 골라 상한가/하한가/스팩여부로 파싱한다")
        void success_filtersByStockCode() {
            when(accessTokenProvider.issueAccessToken()).thenReturn("test-token");
            mockServer.expect(requestTo(ETC_URL))
                    .andRespond(withSuccess("""
                            {"t8436OutBlock":[
                              {"hname":"다른종목","shcode":"000020","uplmtprice":6610,"dnlmtprice":3570,"spac_gubun":"N"},
                              {"hname":"삼성전자","shcode":"005930","uplmtprice":260000,"dnlmtprice":212000,"spac_gubun":"N"}
                            ]}""", MediaType.APPLICATION_JSON));

            Optional<LsStockMasterInfoDto> result = client.getStockMasterInfo(STOCK_CODE);

            assertThat(result).isPresent();
            assertThat(result.get().getStockName()).isEqualTo("삼성전자");
            assertThat(result.get().getUpperLimitPrice()).isEqualTo(260000L);
            assertThat(result.get().isSpac()).isFalse();
        }
    }
}
