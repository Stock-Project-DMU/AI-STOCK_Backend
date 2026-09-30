package com.teamfp.aistock.domain.stock.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;
import com.teamfp.aistock.infra.dart.DartApiClient;
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

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(marketQueryService, "appKey", "test-key");
        ReflectionTestUtils.setField(marketQueryService, "appSecret", "test-secret");
    }

    private List<RankingItemDto> ranking(String stockCode) {
        return List.of(RankingItemDto.builder().rank(1).stockCode(stockCode).build());
    }

    @Test
    @DisplayName("sort=rise는 전체 시장 상승률 상위 순위를 반환한다(#13)")
    void getRankings_rise_returnsWholeMarketRising() {
        when(highItemApiClient.getTopPriceChangeRate()).thenReturn(ranking("000001"));

        List<RankingItemDto> result = marketQueryService.getRankings("rise");

        assertThat(result).extracting(RankingItemDto::getStockCode).containsExactly("000001");
        verify(highItemApiClient, never()).getTopMarketCap();
    }

    @Test
    @DisplayName("sort=fall은 전체 시장 하락률 상위 순위를 반환한다(#13)")
    void getRankings_fall_returnsWholeMarketFalling() {
        when(highItemApiClient.getTopPriceDeclineRate()).thenReturn(ranking("000002"));

        List<RankingItemDto> result = marketQueryService.getRankings("fall");

        assertThat(result).extracting(RankingItemDto::getStockCode).containsExactly("000002");
        verify(highItemApiClient, never()).getTopPriceChangeRate();
    }

    @Test
    @DisplayName("sort=change는 기존 호출 호환을 위해 상승률 상위 순위를 반환한다")
    void getRankings_change_isRiseAlias() {
        when(highItemApiClient.getTopPriceChangeRate()).thenReturn(ranking("000001"));

        List<RankingItemDto> result = marketQueryService.getRankings("change");

        assertThat(result).extracting(RankingItemDto::getStockCode).containsExactly("000001");
    }

    @Test
    @DisplayName("지원하지 않는 sort 값이면 INVALID_INPUT을 던진다")
    void getRankings_unknownSort_throwsInvalidInput() {
        assertThatThrownBy(() -> marketQueryService.getRankings("unknown"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }
}
