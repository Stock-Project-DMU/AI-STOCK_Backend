package com.teamfp.aistock.domain.stock.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.teamfp.aistock.domain.stock.dto.PriceDirection;
import com.teamfp.aistock.domain.stock.dto.StockPriceDto;
import com.teamfp.aistock.domain.stock.dto.response.StockPriceResponse;
import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;
import com.teamfp.aistock.global.redis.RedisStockCacheService;
import com.teamfp.aistock.infra.ls.LsLocalMarketDataReader;
import com.teamfp.aistock.infra.ls.dto.LsCurrentPriceDetailDto;

/**
 * StockService.getCurrentPrice()의 ls.mode=mock/real 분기 단위 테스트
 * (feature/ls-local-data). LsMarketDataApiClientTest의 GetCurrentPriceMockBranch와 동일하게,
 * localMarketDataReader 주입 여부(Optional.empty()=real, Optional.of(...)=mock)로 분기를 갈라
 * 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class StockServiceTest {

    private static final String STOCK_CODE = "005930";

    @Mock
    private RedisStockCacheService redisStockCacheService;

    @Nested
    @DisplayName("현재가 조회 real 분기 (ls.mode=real, localMarketDataReader 없음)")
    class GetCurrentPriceRealBranch {

        @Test
        @DisplayName("Redis 캐시에 tick이 있으면 그 값을 그대로 응답으로 변환한다")
        void success_returnsRedisCachedPrice() {
            StockService service = new StockService(redisStockCacheService, Optional.empty());
            StockPriceDto cached = StockPriceDto.builder()
                    .stockCode(STOCK_CODE)
                    .stockName("삼성전자")
                    .currentPrice(71000L)
                    .changeAmount(500L)
                    .changeRate(0.71)
                    .volume(12345678L)
                    .build();
            when(redisStockCacheService.getStockPrice(STOCK_CODE)).thenReturn(cached);

            StockPriceResponse result = service.getCurrentPrice(STOCK_CODE);

            assertThat(result.stockName()).isEqualTo("삼성전자");
            assertThat(result.currentPrice()).isEqualTo(71000L);
            assertThat(result.direction()).isEqualTo(PriceDirection.UP);
        }

        @Test
        @DisplayName("Redis 캐시가 비어있으면(최근 tick 없음) STOCK_PRICE_NOT_AVAILABLE을 던진다")
        void throws_whenRedisCacheMiss() {
            StockService service = new StockService(redisStockCacheService, Optional.empty());
            when(redisStockCacheService.getStockPrice(STOCK_CODE)).thenReturn(null);

            assertThatThrownBy(() -> service.getCurrentPrice(STOCK_CODE))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.STOCK_PRICE_NOT_AVAILABLE);
        }
    }

    @Nested
    @DisplayName("현재가 조회 mock 분기 (ls.mode=mock, localMarketDataReader 존재)")
    class GetCurrentPriceMockBranch {

        @Mock
        private LsLocalMarketDataReader localMarketDataReader;

        @Test
        @DisplayName("localMarketDataReader가 값을 반환하면 Redis를 거치지 않고 그 값을 응답으로 변환한다")
        void success_usesLocalReader_andSkipsRedis() {
            StockService service = new StockService(redisStockCacheService, Optional.of(localMarketDataReader));
            LsCurrentPriceDetailDto local = LsCurrentPriceDetailDto.builder()
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
            StockService service = new StockService(redisStockCacheService, Optional.of(localMarketDataReader));
            when(localMarketDataReader.getCurrentPrice(STOCK_CODE)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getCurrentPrice(STOCK_CODE))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.STOCK_PRICE_NOT_AVAILABLE);

            verify(redisStockCacheService, never()).getStockPrice(STOCK_CODE);
        }
    }
}
