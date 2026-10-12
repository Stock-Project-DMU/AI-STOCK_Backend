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

import com.teamfp.aistock.domain.order.service.OrderExecutionService;
import com.teamfp.aistock.domain.stock.dto.HogaDto;
import com.teamfp.aistock.domain.stock.dto.StockPriceDto;
import com.teamfp.aistock.domain.stock.dto.response.HogaResponse;
import com.teamfp.aistock.domain.stock.dto.response.StockPriceResponse;
import com.teamfp.aistock.global.redis.RedisStockCacheService;
import com.teamfp.aistock.infra.marketdata.dto.HogaData;
import com.teamfp.aistock.infra.marketdata.dto.TickData;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * StockBroadcastService 검증 테스트. TickData/HogaData를 주입해 Redis 캐싱과 STOMP
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

    @Mock
    private OrderExecutionService orderExecutionService;

    private StockBroadcastService stockBroadcastService;

    @BeforeEach
    void setUp() {
        // 지정가 체결 판단은 실제로는 tickTaskExecutor에서 돌지만, 테스트는 같은 스레드에서 바로 실행해 검증한다.
        stockBroadcastService = new StockBroadcastService(
                redisStockCacheService, stockNameResolver, messagingTemplate, orderExecutionService, Runnable::run);
    }

    @Test
    @DisplayName("체결 tick 수신 시 Redis 캐싱과 STOMP 브로드캐스팅이 함께 호출된다")
    void onTickReceived_CachesAndBroadcasts() {
        TickData tickData = TickData.builder()
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
        verify(orderExecutionService).checkAndExecute("005930", 75000L);
    }

    @Test
    @DisplayName("우리 DB 어디에도 종목명이 없으면 stockCode를 이름 대신 사용하고, 하락 종목은 등락 금액에 음수 부호를 붙인다")
    void onTickReceived_FallsBackToStockCodeAndAppliesDownSign() {
        TickData tickData = TickData.builder()
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
        TickData tickData = TickData.builder()
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
        // Throttle되어 무시된 두 번째 tick은 지정가 체결 판단도 함께 건너뛴다 — 다음 tick(200ms
        // 이후)에서 다시 시도되므로 안전하다(OrderExecutionService.checkAndExecute()는 멱등).
        verify(orderExecutionService, times(1)).checkAndExecute(eq("005930"), anyLong());
    }

    @Test
    @DisplayName("200ms 안에 걸린 tick·호가는 버리지 않고 창이 끝나면 마지막 값 1건을 보낸다(trailing, 2026-10-12 코드리뷰)")
    void onTickReceived_SendsLastThrottledTickAfterWindow() {
        TickData first = TickData.builder().stockCode("005930").stockName("삼성전자").currentPrice(75000)
                .changeRate(0.0).changeAmount(0).volume(100).tradedAt(LocalDateTime.now()).build();
        TickData middle = TickData.builder().stockCode("005930").stockName("삼성전자").currentPrice(75100)
                .changeRate(0.1).changeAmount(100).volume(200).tradedAt(LocalDateTime.now()).build();
        TickData last = TickData.builder().stockCode("005930").stockName("삼성전자").currentPrice(75200)
                .changeRate(0.3).changeAmount(200).volume(300).tradedAt(LocalDateTime.now()).build();
        HogaData hoga1 = HogaData.builder().stockCode("005930").askPrices(List.of(75100L)).build();
        HogaData hoga2 = HogaData.builder().stockCode("005930").askPrices(List.of(75300L)).build();

        stockBroadcastService.onTickReceived(first);
        stockBroadcastService.onTickReceived(middle);
        stockBroadcastService.onTickReceived(last);
        stockBroadcastService.onHogaReceived(hoga1);
        stockBroadcastService.onHogaReceived(hoga2);

        // 첫 tick은 바로, 창 안의 나머지는 마지막 것(75,200원) 1건만 창이 끝난 뒤 나간다 — 지정가 체결 판단도 마지막 가격으로 한 번 더.
        verify(orderExecutionService, org.mockito.Mockito.timeout(1000).times(1)).checkAndExecute("005930", 75200L);
        verify(orderExecutionService, times(1)).checkAndExecute("005930", 75000L);
        verify(orderExecutionService, org.mockito.Mockito.never()).checkAndExecute("005930", 75100L);
        verify(messagingTemplate, org.mockito.Mockito.timeout(1000).times(2))
                .convertAndSend(eq("/topic/stock/005930/hoga"), any(HogaResponse.class));
    }

    @Test
    @DisplayName("늦게 보내려던 tick보다 새 tick이 먼저 나가면 옛 tick은 버린다 — 가격이 뒤로 가지 않게(2026-10-12 마지막 리뷰)")
    @SuppressWarnings("unchecked")
    void onTickReceived_DropsOlderPendingWhenNewerSent() throws Exception {
        TickData first = TickData.builder().stockCode("005930").stockName("삼성전자").currentPrice(75000)
                .changeRate(0.0).changeAmount(0).volume(100).tradedAt(LocalDateTime.now()).build();
        TickData older = TickData.builder().stockCode("005930").stockName("삼성전자").currentPrice(75100)
                .changeRate(0.1).changeAmount(100).volume(200).tradedAt(LocalDateTime.now()).build();
        TickData newer = TickData.builder().stockCode("005930").stockName("삼성전자").currentPrice(75300)
                .changeRate(0.4).changeAmount(300).volume(300).tradedAt(LocalDateTime.now()).build();

        stockBroadcastService.onTickReceived(first);
        stockBroadcastService.onTickReceived(older); // 창 안 → 대기
        // 창이 끝나기 전에 다른 경로로 더 새 tick이 나간 상황을 만든다(200ms 제한 기록을 비워 바로 통과시킴).
        ((ConcurrentHashMap<String, Long>) org.springframework.test.util.ReflectionTestUtils
                .getField(stockBroadcastService, "lastTickProcessedAt")).clear();
        stockBroadcastService.onTickReceived(newer);
        Thread.sleep(400);

        verify(orderExecutionService).checkAndExecute("005930", 75000L);
        verify(orderExecutionService).checkAndExecute("005930", 75300L);
        verify(orderExecutionService, org.mockito.Mockito.never()).checkAndExecute("005930", 75100L);
    }

    @Test
    @DisplayName("호가 수신 시 Redis 캐싱과 /hoga 토픽 STOMP 브로드캐스팅이 함께 호출된다")
    void onHogaReceived_CachesAndBroadcastsToHogaTopic() {
        HogaData hogaData = HogaData.builder()
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
