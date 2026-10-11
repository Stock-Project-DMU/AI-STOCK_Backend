package com.teamfp.aistock.domain.stock.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.teamfp.aistock.domain.stock.service.MarketQueryService;
import com.teamfp.aistock.global.exception.GlobalExceptionHandler;
import com.teamfp.aistock.infra.marketdata.HighItemApiClient;
import com.teamfp.aistock.infra.marketdata.LocalMarketDataReader;

/**
 * 순위 조회를 HTTP 응답 수준에서 확인한다 — 로컬 시세 파일(market_data.json)만 임시 디렉토리에 두고, LocalMarketDataReader →
 * HighItemApiClient → MarketQueryService → MarketQueryController → GlobalExceptionHandler는 실제 클래스를 그대로 거친다
 * (fix/local-market-data-stable — 이전에는 외부 시세 데이터 제공사 응답을 흉내 내 503 장애 경로를 봤다).
 */
class MarketQueryControllerTest {

    private static final String RANKINGS_PATH = "/api/market/rankings";

    @TempDir
    private Path tempDir;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        LocalMarketDataReader reader = new LocalMarketDataReader();
        ReflectionTestUtils.setField(reader, "localDataPath", tempDir.toString());
        // 순위 조회 경로에서 쓰지 않는 클라이언트(현재가·뉴스·업종·투자정보·DART)는 null로 둔다.
        MarketQueryService marketQueryService = new MarketQueryService(new HighItemApiClient(reader), null, null, null, null, null);
        mockMvc = MockMvcBuilders.standaloneSetup(new MarketQueryController(marketQueryService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("시세 파일이 없으면(생성기 미실행) 오류 대신 200 + 빈 목록을 반환한다")
    void rankings_returns200WithEmptyList_whenNoLocalData() throws Exception {
        mockMvc.perform(get(RANKINGS_PATH).param("sort", "market-cap"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    @DisplayName("시세 파일의 종목을 시가총액 순으로 정렬해 반환하고, _market 같은 비종목 키는 순위에 넣지 않는다")
    void rankings_returnsSortedStocksFromLocalData() throws Exception {
        Files.writeString(tempDir.resolve("market_data.json"), """
                {"005930": {"stockCode":"005930","stockName":"삼성전자","currentPrice":70000,"changeRate":1.0,"volume":10,"listingShares":5000},
                 "000660": {"stockCode":"000660","stockName":"SK하이닉스","currentPrice":200000,"changeRate":-1.0,"volume":20,"listingShares":700},
                 "_market": {"hotThemes": []}}""");

        mockMvc.perform(get(RANKINGS_PATH).param("sort", "market-cap"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].stockCode").value("005930"))
                .andExpect(jsonPath("$.data[1].stockCode").value("000660"));
    }

    @Test
    @DisplayName("지원하지 않는 정렬 기준이면 400을 반환한다")
    void rankings_returns400_whenSortUnknown() throws Exception {
        writeEmpty();
        mockMvc.perform(get(RANKINGS_PATH).param("sort", "unknown"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    private void writeEmpty() throws IOException {
        Files.writeString(tempDir.resolve("market_data.json"), "{}");
    }
}
