package com.teamfp.aistock.infra.ls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;

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

import com.teamfp.aistock.infra.ls.dto.LsThemeConstituentDto;
import com.teamfp.aistock.infra.ls.dto.LsThemeDto;

@ExtendWith(MockitoExtension.class)
class LsSectorApiClientTest {

    private static final String SECTOR_URL = "http://test-ls/stock/sector";
    private static final String STOCK_CODE = "005930";

    @Mock
    private LsAccessTokenProvider accessTokenProvider;

    private MockRestServiceServer mockServer;
    private LsSectorApiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();

        client = new LsSectorApiClient(accessTokenProvider, builder);
        ReflectionTestUtils.setField(client, "sectorUrl", SECTOR_URL);
    }

    @Nested
    @DisplayName("종목별테마 조회 (getThemesForStock, t1532)")
    class GetThemesForStock {

        @Test
        @DisplayName("t1532OutBlock 배열을 테마 목록으로 파싱한다")
        void success_parsesThemeRows() {
            when(accessTokenProvider.issueAccessToken()).thenReturn("test-token");
            mockServer.expect(requestTo(SECTOR_URL))
                    .andRespond(withSuccess("""
                            {"t1532OutBlock":[
                              {"tmname":"HBM(고대역폭메모리)","tmcode":"0536"}
                            ]}""", MediaType.APPLICATION_JSON));

            List<LsThemeDto> result = client.getThemesForStock(STOCK_CODE);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getThemeName()).isEqualTo("HBM(고대역폭메모리)");
            mockServer.verify();
        }

        @Test
        @DisplayName("t1532OutBlock이 없으면 빈 리스트를 반환한다")
        void empty_whenOutBlockMissing() {
            when(accessTokenProvider.issueAccessToken()).thenReturn("test-token");
            mockServer.expect(requestTo(SECTOR_URL))
                    .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

            List<LsThemeDto> result = client.getThemesForStock(STOCK_CODE);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("특이테마 조회 (getHotThemes, t1533)")
    class GetHotThemes {

        @Test
        @DisplayName("t1533OutBlock1 배열을 통계 문구 포함 핫테마 목록으로 파싱한다")
        void success_parsesHotThemeRows() {
            when(accessTokenProvider.issueAccessToken()).thenReturn("test-token");
            mockServer.expect(requestTo(SECTOR_URL))
                    .andRespond(withSuccess("""
                            {"t1533OutBlock1":[
                              {"tmname":"광통신","tmcode":"0586","totcnt":14,"upcnt":13,"uprate":"92.86","diff_vol":"83.15"}
                            ]}""", MediaType.APPLICATION_JSON));

            List<LsThemeDto> result = client.getHotThemes();

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getThemeName()).isEqualTo("광통신");
            assertThat(result.get(0).getStats()).contains("13/14종목").contains("92.86%");
            mockServer.verify();
        }
    }

    @Nested
    @DisplayName("테마명으로 구성종목 조회 (getThemeConstituentsByName, t8425+t1537)")
    class GetThemeConstituentsByName {

        @Test
        @DisplayName("전체테마 목록(t8425)에서 부분일치로 테마코드를 찾은 뒤 구성종목(t1537)을 조회한다")
        void success_resolvesCodeThenFetchesConstituents() {
            when(accessTokenProvider.issueAccessToken()).thenReturn("test-token");
            mockServer.expect(requestTo(SECTOR_URL))
                    .andRespond(withSuccess("""
                            {"t8425OutBlock":[
                              {"tmname":"반도체 장비","tmcode":"0012"}
                            ]}""", MediaType.APPLICATION_JSON));
            mockServer.expect(requestTo(SECTOR_URL))
                    .andRespond(withSuccess("""
                            {"t1537OutBlock1":[
                              {"hname":"티씨케이","shcode":"064760","price":202000,"change":34700,"diff":"20.74","volume":93841}
                            ]}""", MediaType.APPLICATION_JSON));

            List<LsThemeConstituentDto> result = client.getThemeConstituentsByName("반도체");

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getStockName()).isEqualTo("티씨케이");
            mockServer.verify();
        }

        @Test
        @DisplayName("전체테마 목록에 일치하는 테마가 없으면 빈 리스트를 반환한다(t1537 호출 안 함)")
        void empty_whenNoMatchingTheme() {
            when(accessTokenProvider.issueAccessToken()).thenReturn("test-token");
            mockServer.expect(requestTo(SECTOR_URL))
                    .andRespond(withSuccess("""
                            {"t8425OutBlock":[
                              {"tmname":"반도체 장비","tmcode":"0012"}
                            ]}""", MediaType.APPLICATION_JSON));

            List<LsThemeConstituentDto> result = client.getThemeConstituentsByName("존재하지않는테마");

            assertThat(result).isEmpty();
            mockServer.verify();
        }
    }
}
