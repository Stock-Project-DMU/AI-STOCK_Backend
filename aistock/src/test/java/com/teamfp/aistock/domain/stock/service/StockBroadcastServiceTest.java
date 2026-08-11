package com.teamfp.aistock.domain.stock.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import com.teamfp.aistock.domain.stock.dto.HogaDto;
import com.teamfp.aistock.domain.stock.dto.StockPriceDto;
import com.teamfp.aistock.domain.stock.dto.response.HogaResponse;
import com.teamfp.aistock.domain.stock.dto.response.StockPriceResponse;
import com.teamfp.aistock.global.redis.RedisStockCacheService;
import com.teamfp.aistock.infra.ls.dto.LsHogaData;
import com.teamfp.aistock.infra.ls.dto.LsTickData;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * StockBroadcastService 검증 테스트. LsTickData/LsHogaData를 주입해 Redis 캐싱과 STOMP
 * 브로드캐스팅이 함께 호출되는지, 종목명 폴백과 Throttle(200ms)이 의도대로 동작하는지 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class StockBroadcastServiceTest {

    @Mock
    private RedisStockCacheService redisStockCacheService;

    @Mock
    private StockNameResolver stockNameResolver;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private StockBroadcastService stockBroadcastService;

    @BeforeEach
    void setUp() {
        stockBroadcastService = new StockBroadcastService(redisStockCacheService, stockNameResolver, messagingTemplate);
    }

    @Test
    @DisplayName("체결 tick 수신 시 Redis 캐싱과 STOMP 브로드캐스팅이 함께 호출된다")
    void onTickReceived_CachesAndBroadcasts() {
        LsTickData tickData = LsTickData.builder()
                .stockCode("005930")
                .stockName(null)
                .currentPrice(75000)
                .changeRate(0.67)
                .changeAmount(500)
                .volume(1234567)
                .tradedAt(LocalDateTime.now())
                .build();
        when(stockNameResolver.resolveStockName("005930")).thenReturn("삼성전자");

        stockBroadcastService.onTickReceived(tickData);

        ArgumentCaptor<StockPriceDto> dtoCaptor = ArgumentCaptor.forClass(StockPriceDto.class);
        verify(redisStockCacheService).saveStockPrice(eq("005930"), dtoCaptor.capture());
        assertThat(dtoCaptor.getValue().getStockName()).isEqualTo("삼성전자");
        assertThat(dtoCaptor.getValue().getChangeAmount()).isEqualTo(500);

        verify(messagingTemplate).convertAndSend(eq("/topic/stock/005930"), any(StockPriceResponse.class));
    }

    @Test
    @DisplayName("우리 DB 어디에도 종목명이 없으면 stockCode를 이름 대신 사용하고, 하락 종목은 등락 금액에 음수 부호를 붙인다")
    void onTickReceived_FallsBackToStockCodeAndAppliesDownSign() {
        LsTickData tickData = LsTickData.builder()
                .stockCode("000660")
                .stockName(null)
                .currentPrice(120000)
                .changeRate(-1.5)
                .changeAmount(1800)
                .volume(500000)
                .tradedAt(LocalDateTime.now())
                .build();
        when(stockNameResolver.resolveStockName("000660")).thenReturn(null);

        stockBroadcastService.onTickReceived(tickData);

        ArgumentCaptor<StockPriceDto> dtoCaptor = ArgumentCaptor.forClass(StockPriceDto.class);
        verify(redisStockCacheService).saveStockPrice(eq("000660"), dtoCaptor.capture());
        assertThat(dtoCaptor.getValue().getStockName()).isEqualTo("000660");
        assertThat(dtoCaptor.getValue().getChangeAmount()).isEqualTo(-1800);
    }

    @Test
    @DisplayName("같은 종목의 tick이 200ms 이내에 연속으로 오면 두 번째부터는 무시된다(Throttle)")
    void onTickReceived_ThrottlesWithin200ms() {
        LsTickData tickData = LsTickData.builder()
                .stockCode("005930")
                .stockName("삼성전자")
                .currentPrice(75000)
                .changeRate(0.0)
                .changeAmount(0)
                .volume(100)
                .tradedAt(LocalDateTime.now())
                .build();

        stockBroadcastService.onTickReceived(tickData);
        stockBroadcastService.onTickReceived(tickData);

        verify(redisStockCacheService, times(1)).saveStockPrice(eq("005930"), any());
        verify(messagingTemplate, times(1)).convertAndSend(eq("/topic/stock/005930"), any(StockPriceResponse.class));
    }

    @Test
    @DisplayName("호가 수신 시 Redis 캐싱과 /hoga 토픽 STOMP 브로드캐스팅이 함께 호출된다")
    void onHogaReceived_CachesAndBroadcastsToHogaTopic() {
        LsHogaData hogaData = LsHogaData.builder()
                .stockCode("005930")
                .askPrices(List.of(75100L))
                .askVolumes(List.of(100L))
                .bidPrices(List.of(75000L))
                .bidVolumes(List.of(150L))
                .build();

        stockBroadcastService.onHogaReceived(hogaData);

        verify(redisStockCacheService).saveHogaData(eq("005930"), any(HogaDto.class));
        verify(messagingTemplate).convertAndSend(eq("/topic/stock/005930/hoga"), any(HogaResponse.class));
    }

    @Test
    @DisplayName("같은 종목코드로 여러 스레드가 동시에 isThrottled()를 호출해도 정확히 하나만 통과(false)한다")
    void isThrottled_OnlyOneThreadPassesUnderConcurrency() throws InterruptedException {
        ConcurrentHashMap<String, Long> lastProcessedAt = new ConcurrentHashMap<>();
        String stockCode = "005930";
        int threadCount = 50;

        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger passedCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executorService.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    if (!stockBroadcastService.isThrottled(lastProcessedAt, stockCode)) {
                        passedCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown();
        boolean completed = doneLatch.await(5, TimeUnit.SECONDS);
        executorService.shutdown();

        assertThat(completed).isTrue();
        assertThat(passedCount.get()).isEqualTo(1);
    }
}
