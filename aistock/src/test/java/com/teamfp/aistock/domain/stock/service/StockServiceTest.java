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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.teamfp.aistock.domain.stock.dto.HogaDto;
import com.teamfp.aistock.domain.stock.dto.PriceDirection;
import com.teamfp.aistock.domain.stock.dto.StockPriceDto;
import com.teamfp.aistock.domain.stock.dto.response.HogaResponse;
import com.teamfp.aistock.domain.stock.dto.response.StockPriceResponse;
import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;
import com.teamfp.aistock.global.redis.RedisStockCacheService;
import com.teamfp.aistock.infra.marketdata.LocalMarketDataReader;
import com.teamfp.aistock.infra.marketdata.dto.CurrentPriceDetailDto;
import com.teamfp.aistock.infra.marketdata.dto.HogaData;

/**
 * StockService.getCurrentPrice()/getHoga() 단위 테스트
 * (feature/ls-local-data). MarketDataApiClientTest의 GetCurrentPriceMockBranch와 동일하게,
 * 시세는 로컬 시세 데이터(LocalMarketDataReader)만 읽고 Redis 캐시로 폴백하지 않는지
 * 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class StockServiceTest {

    private static final String STOCK_CODE = "005930";

    @Mock
    private RedisStockCacheService redisStockCacheService;

    @Nested
    @DisplayName("현재가 조회 mock 분기 (로컬 시세 데이터)")
    class GetCurrentPriceMockBranch {

        @Mock
        private LocalMarketDataReader localMarketDataReader;

        @Test
        @DisplayName("localMarketDataReader가 값을 반환하면 Redis를 거치지 않고 그 값을 응답으로 변환한다")
        void success_usesLocalReader_andSkipsRedis() {
            StockService service = new StockService(localMarketDataReader);
            CurrentPriceDetailDto local = CurrentPriceDetailDto.builder()
                    .stockCode(STOCK_CODE)
                    .stockName("삼성전자(로컬)")
                    .currentPrice(70000L)
                    .changeAmount(-500L)
                    .changeRate(-0.71)
                    .volume(999L)
                    .build();
            when(localMarketDataReader.getCurrentPrice(STOCK_CODE)).thenReturn(Optional.of(local));

            StockPriceResponse result = service.getCurrentPrice(STOCK_CODE);

            assertThat(result.stockName()).isEqualTo("삼성전자(로컬)");
            assertThat(result.currentPrice()).isEqualTo(70000L);
            assertThat(result.changeAmount()).isEqualTo(-500L);
            assertThat(result.direction()).isEqualTo(PriceDirection.DOWN);
            verifyNoInteractions(redisStockCacheService);
        }

        @Test
        @DisplayName("localMarketDataReader가 빈 값을 반환하면 Redis로 폴백하지 않고 STOCK_PRICE_NOT_AVAILABLE을 던진다")
        void throws_whenLocalReaderEmpty_noFallbackToRedis() {
            StockService service = new StockService(localMarketDataReader);
            when(localMarketDataReader.getCurrentPrice(STOCK_CODE)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getCurrentPrice(STOCK_CODE))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.STOCK_PRICE_NOT_AVAILABLE);

            verify(redisStockCacheService, never()).getStockPrice(STOCK_CODE);
        }
    }

    @Nested
    @DisplayName("호가 조회 mock 분기 (로컬 시세 데이터)")
    class GetHogaMockBranch {

        @Mock
        private LocalMarketDataReader localMarketDataReader;

        @Test
        @DisplayName("localMarketDataReader가 값을 반환하면 Redis를 거치지 않고 그 값을 응답으로 변환한다")
        void success_usesLocalReader_andSkipsRedis() {
            StockService service = new StockService(localMarketDataReader);
            HogaData local = HogaData.builder()
                    .stockCode(STOCK_CODE)
                    .askPrices(List.of(78600L, 78700L, 78800L, 78900L, 79000L))
                    .askVolumes(List.of(100L, 200L, 300L, 400L, 500L))
                    .bidPrices(List.of(78500L, 78400L, 78300L, 78200L, 78100L))
                    .bidVolumes(List.of(150L, 250L, 350L, 450L, 550L))
                    .build();
            when(localMarketDataReader.getHoga(STOCK_CODE)).thenReturn(Optional.of(local));

            HogaResponse result = service.getHoga(STOCK_CODE);

            assertThat(result.stockCode()).isEqualTo(STOCK_CODE);
            assertThat(result.askPrices()).isEqualTo(List.of(78600L, 78700L, 78800L, 78900L, 79000L));
            assertThat(result.bidVolumes()).isEqualTo(List.of(150L, 250L, 350L, 450L, 550L));
            verifyNoInteractions(redisStockCacheService);
        }

        @Test
        @DisplayName("localMarketDataReader가 빈 값을 반환하면 Redis로 폴백하지 않고 STOCK_PRICE_NOT_AVAILABLE을 던진다")
        void throws_whenLocalReaderEmpty_noFallbackToRedis() {
            StockService service = new StockService(localMarketDataReader);
            when(localMarketDataReader.getHoga(STOCK_CODE)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getHoga(STOCK_CODE))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.STOCK_PRICE_NOT_AVAILABLE);

            verify(redisStockCacheService, never()).getHogaData(STOCK_CODE);
        }
    }
}
