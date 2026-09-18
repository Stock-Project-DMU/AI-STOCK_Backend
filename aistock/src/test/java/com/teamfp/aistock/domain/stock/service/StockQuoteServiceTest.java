package com.teamfp.aistock.domain.stock.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import com.teamfp.aistock.domain.stock.dto.StockPriceDto;
import com.teamfp.aistock.global.redis.RedisStockCacheService;
import com.teamfp.aistock.infra.ls.LsMarketDataApiClient;
import com.teamfp.aistock.infra.ls.dto.LsCurrentPriceDetailDto;

class StockQuoteServiceTest {
    private final RedisStockCacheService redis = mock(RedisStockCacheService.class);
    private final LsMarketDataApiClient ls = mock(LsMarketDataApiClient.class);
    private final StockQuoteService service = new StockQuoteService(redis, ls);

    @Test void liveQuoteAvoidsExternalRequest() {
        var quote = StockPriceDto.builder().stockName("삼성전자").currentPrice(70000).build();
        when(redis.getStockPrice("005930")).thenReturn(quote);
        assertThat(service.getStockPrice("005930")).isSameAs(quote);
        verifyNoInteractions(ls);
    }

    @Test void missingTickUsesServerQuoteWithoutPublishingFakeTick() {
        when(ls.getCurrentPrice("005930")).thenReturn(Optional.of(LsCurrentPriceDetailDto.builder()
                .stockCode("005930").stockName("삼성전자").currentPrice(71000).build()));
        var quote = service.getStockPrice("005930");
        assertThat(quote.getStockName()).isEqualTo("삼성전자");
        assertThat(quote.getCurrentPrice()).isEqualTo(71000);
        verify(redis, never()).saveStockPrice(anyString(), any());
    }

    @Test void unavailableOrInvalidQuoteCannotBeUsedForOrders() {
        when(ls.getCurrentPrice("005930")).thenReturn(Optional.empty());
        assertThat(service.getStockPrice("005930")).isNull();
        when(ls.getCurrentPrice("005930")).thenReturn(Optional.of(LsCurrentPriceDetailDto.builder()
                .stockName("삼성전자").currentPrice(0).build()));
        assertThat(service.getStockPrice("005930")).isNull();
    }
}
