package com.teamfp.aistock.domain.stock.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;
import com.teamfp.aistock.infra.dart.DartApiClient;
import com.teamfp.aistock.infra.dart.dto.DartFinancialRequest;
import com.teamfp.aistock.infra.dart.dto.DartFinancialResponse;
import com.teamfp.aistock.infra.dart.dto.ListedStock;
import com.teamfp.aistock.infra.marketdata.HighItemApiClient;
import com.teamfp.aistock.infra.marketdata.IndustryApiClient;
import com.teamfp.aistock.infra.marketdata.InvestInfoApiClient;
import com.teamfp.aistock.infra.marketdata.MarketDataApiClient;
import com.teamfp.aistock.infra.marketdata.dto.RankingItemDto;
import com.teamfp.aistock.infra.naver.NaverNewsApiClient;

@ExtendWith(MockitoExtension.class)
class MarketQueryServiceTest {

    @Mock private HighItemApiClient highItemApiClient;
    @Mock private MarketDataApiClient marketDataApiClient;
    @Mock private NaverNewsApiClient naverNewsApiClient;
    @Mock private IndustryApiClient industryApiClient;
    @Mock private InvestInfoApiClient investInfoApiClient;
    @Mock private DartApiClient dartApiClient;

    @InjectMocks
    private MarketQueryService marketQueryService;

    private List<RankingItemDto> ranking(String stockCode) {
        return List.of(RankingItemDto.builder().rank(1).stockCode(stockCode).build());
    }

    @Test
    @DisplayName("종목 추천은 상장 종목명과 코드를 반환한다")
    void suggestStocks_returnsListedMatches() {
        when(dartApiClient.searchListedStocks("삼성", 8))
                .thenReturn(List.of(new ListedStock("005930", "삼성전자")));

        assertThat(marketQueryService.suggestStocks("삼성"))
                .singleElement()
                .satisfies(stock -> {
                    assertThat(stock.stockCode()).isEqualTo("005930");
                    assertThat(stock.stockName()).isEqualTo("삼성전자");
                });
    }

    @Test
    @DisplayName("재무 조회는 LS 현재가 호출 없이 DART 종목코드로 회사 코드를 찾는다")
    void getResearch_finance_doesNotDependOnMarketQuote() {
        String corpCode = "00126380";
        int year = java.time.LocalDate.now().getYear() - 1;
        DartFinancialRequest request = new DartFinancialRequest(corpCode, year);
        DartFinancialResponse financials = new DartFinancialResponse(corpCode, year, 1L, null, null, null, null, null);
        when(dartApiClient.resolveCorpCodeByStockCode("005935")).thenReturn(Optional.of(corpCode));
        when(dartApiClient.getFinancials(request)).thenReturn(financials);

        assertThat(marketQueryService.getResearch("005935", "finance")).isSameAs(financials);
        verifyNoInteractions(marketDataApiClient);
    }

    @Test
    @DisplayName("sort=rise는 전체 시장 상승률 상위 순위를 반환한다(#13)")
    void getRankings_rise_returnsWholeMarketRising() {
        when(highItemApiClient.getTopPriceChangeRate()).thenReturn(ranking("000001"));

        List<RankingItemDto> result = marketQueryService.getRankings("rise", false);

        assertThat(result).extracting(RankingItemDto::getStockCode).containsExactly("000001");
        verify(highItemApiClient, never()).getTopMarketCap();
    }

    @Test
    @DisplayName("sort=fall은 전체 시장 하락률 상위 순위를 반환한다(#13)")
    void getRankings_fall_returnsWholeMarketFalling() {
        when(highItemApiClient.getTopPriceDeclineRate()).thenReturn(ranking("000002"));

        List<RankingItemDto> result = marketQueryService.getRankings("fall", false);

        assertThat(result).extracting(RankingItemDto::getStockCode).containsExactly("000002");
        verify(highItemApiClient, never()).getTopPriceChangeRate();
    }

    @Test
    @DisplayName("sort=change는 기존 호출 호환을 위해 상승률 상위 순위를 반환한다")
    void getRankings_change_isRiseAlias() {
        when(highItemApiClient.getTopPriceChangeRate()).thenReturn(ranking("000001"));

        List<RankingItemDto> result = marketQueryService.getRankings("change", false);

        assertThat(result).extracting(RankingItemDto::getStockCode).containsExactly("000001");
    }

    @Test
    @DisplayName("지원하지 않는 sort 값이면 INVALID_INPUT을 던진다")
    void getRankings_unknownSort_throwsInvalidInput() {
        assertThatThrownBy(() -> marketQueryService.getRankings("unknown", false))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("all=true면 홈 무한 스크롤용으로 건수 제한 없는 순위 메서드를 호출한다")
    void getRankings_all_requestsUnlimitedRanking() {
        when(highItemApiClient.getTopMarketCap(Integer.MAX_VALUE)).thenReturn(ranking("000003"));

        List<RankingItemDto> result = marketQueryService.getRankings("market-cap", true);

        assertThat(result).extracting(RankingItemDto::getStockCode).containsExactly("000003");
        verify(highItemApiClient, never()).getTopMarketCap();
    }

    @Test
    @DisplayName("all=false면 기존처럼 상위 10건 순위 메서드를 호출한다")
    void getRankings_notAll_keepsDefaultRanking() {
        when(highItemApiClient.getTopVolume()).thenReturn(ranking("000004"));

        List<RankingItemDto> result = marketQueryService.getRankings("volume", false);

        assertThat(result).extracting(RankingItemDto::getStockCode).containsExactly("000004");
        verify(highItemApiClient, never()).getTopVolume(Integer.MAX_VALUE);
    }

    @Test
    @DisplayName("all=true여도 지원하지 않는 sort 값이면 INVALID_INPUT을 던진다")
    void getRankings_allWithUnknownSort_throwsInvalidInput() {
        assertThatThrownBy(() -> marketQueryService.getRankings("unknown", true))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("차트 조회는 dwmcode·count를 그대로 차트 전용 메서드에 넘기고 AI 상담용 months 경로는 쓰지 않는다")
    void getChartHistory_passesDwmcodeAndCount() {
        List<com.teamfp.aistock.infra.marketdata.dto.HistoricalPriceDto> weekly =
                List.of(com.teamfp.aistock.infra.marketdata.dto.HistoricalPriceDto.builder().date("20261001").build());
        when(marketDataApiClient.getChartPrices("005930", 2, 52)).thenReturn(weekly);

        assertThat(marketQueryService.getChartHistory("005930", 2, 52)).isSameAs(weekly);
        verify(marketDataApiClient, never()).getHistoricalPrices(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("차트 조회의 dwmcode가 1~3 밖이거나 count가 1~60 밖이면 INVALID_INPUT을 던진다")
    void getChartHistory_invalidInput_throws() {
        for (int[] invalid : new int[][] {{0, 60}, {4, 60}, {1, 0}, {1, 61}}) {
            assertThatThrownBy(() -> marketQueryService.getChartHistory("005930", invalid[0], invalid[1]))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_INPUT);
        }
        verifyNoInteractions(marketDataApiClient);
    }
}
