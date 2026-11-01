package com.teamfp.aistock.global.redis;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.teamfp.aistock.domain.order.dto.PendingOrderDto;
import com.teamfp.aistock.domain.order.repository.OrderRepository;
import com.teamfp.aistock.domain.stock.dto.StockPriceDto;

import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * RedisPendingOrderService / RedisRateLimiterService / RedisStockCacheService 검증 테스트.
 * ObjectMapper를 tools.jackson(Jackson 3)으로 교체한 뒤 직렬화/역직렬화가 정상 동작하는지 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class RedisMarketServicesTest {

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private ListOperations<String, String> listOperations;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private OrderRepository orderRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private RedisPendingOrderService redisPendingOrderService;
    private RedisStockCacheService redisStockCacheService;
    private RedisRateLimiterService redisRateLimiterService;

    @BeforeEach
    void setUp() {
        redisPendingOrderService = new RedisPendingOrderService(redisTemplate, objectMapper, orderRepository);
        redisStockCacheService = new RedisStockCacheService(redisTemplate, objectMapper);
        redisRateLimiterService = new RedisRateLimiterService(redisTemplate);
    }

    @Test
    @DisplayName("지정가 미체결 주문 추가 - JSON 직렬화 후 rightPush 호출")
    void addPendingOrder_Success() {
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        PendingOrderDto dto = PendingOrderDto.builder()
                .orderId(1L).userId(1L).accountId(1L).orderType("BUY")
                .limitPrice(70000L).quantity(10).stockCode("005930").stockName("삼성전자")
                .build();

        redisPendingOrderService.addPendingOrder("005930", dto);

        verify(listOperations).rightPush(eq("pending:orders:005930"), anyString());
    }

    @Test
    @DisplayName("지정가 미체결 주문 조회 - JSON 역직렬화 성공")
    void getPendingOrders_Success() {
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        PendingOrderDto dto = PendingOrderDto.builder()
                .orderId(1L).userId(1L).accountId(1L).orderType("SELL")
                .limitPrice(80000L).quantity(5).stockCode("005930").stockName("삼성전자")
                .build();
        String json = objectMapper.writeValueAsString(dto);
        when(listOperations.range("pending:orders:005930", 0, -1)).thenReturn(List.of(json));

        List<PendingOrderDto> result = redisPendingOrderService.getPendingOrders("005930");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getOrderId()).isEqualTo(1L);
        assertThat(result.get(0).getOrderType()).isEqualTo("SELL");
    }

    @Test
    @DisplayName("미체결 주문 제거 - orderId 일치 항목만 LREM")
    void removePendingOrder_Success() {
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        PendingOrderDto dto = PendingOrderDto.builder()
                .orderId(1L).userId(1L).accountId(1L).orderType("BUY")
                .limitPrice(70000L).quantity(10).stockCode("005930").stockName("삼성전자")
                .build();
        String json = objectMapper.writeValueAsString(dto);
        when(listOperations.range("pending:orders:005930", 0, -1)).thenReturn(List.of(json));

        when(listOperations.remove("pending:orders:005930", 1, json)).thenReturn(1L);

        boolean removed = redisPendingOrderService.removePendingOrder("005930", 1L);

        verify(listOperations).remove("pending:orders:005930", 1, json);
        assertThat(removed).isTrue();
    }

    @Test
    @DisplayName("미체결 주문 제거 - 이미 리스트에 없는 주문이면 false를 반환하고 LREM하지 않는다")
    void removePendingOrder_ReturnsFalse_WhenAlreadyRemoved() {
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(listOperations.range("pending:orders:005930", 0, -1)).thenReturn(List.of());

        boolean removed = redisPendingOrderService.removePendingOrder("005930", 1L);

        assertThat(removed).isFalse();
        verify(listOperations, never()).remove(anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("현재가 캐시 저장/조회 - Jackson 3(tools.jackson) 직렬화 정상 동작")
    void saveAndGetStockPrice_Success() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        StockPriceDto dto = StockPriceDto.builder()
                .stockCode("005930").stockName("삼성전자").currentPrice(75000)
                .changeAmount(500).changeRate(0.67).volume(1234567)
                .updatedAt(LocalDateTime.now())
                .build();

        redisStockCacheService.saveStockPrice("005930", dto);

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq("stock:price:005930"), jsonCaptor.capture(), eq(Duration.ofSeconds(5)));

        when(valueOperations.get("stock:price:005930")).thenReturn(jsonCaptor.getValue());
        StockPriceDto result = redisStockCacheService.getStockPrice("005930");

        assertThat(result.getStockCode()).isEqualTo("005930");
        assertThat(result.getCurrentPrice()).isEqualTo(75000);
    }

    @Test
    @DisplayName("시뮬레이터 Rate Limiter - 오늘 3회 미만이면 허용(분당 제한 없음)")
    void isAllowed_True() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("gemini:rate:1:daily")).thenReturn("2");

        boolean allowed = redisRateLimiterService.isAllowed(1L);

        assertThat(allowed).isTrue();
        verify(valueOperations, never()).get("gemini:rate:1:minute");
    }

    @Test
    @DisplayName("시뮬레이터 Rate Limiter - 오늘 3회를 다 쓰면 거절")
    void isAllowed_False_DailyExceeded() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("gemini:rate:1:daily")).thenReturn("3");

        boolean allowed = redisRateLimiterService.isAllowed(1L);

        assertThat(allowed).isFalse();
    }

    @Test
    @DisplayName("시뮬레이터 Rate Limiter - 오늘 첫 실행이면 다음 자정(KST)까지 TTL 설정")
    void increment_FirstCall_SetsTtlUntilMidnight() {
        // 2026-11-01 21:30 KST → 자정까지 2시간 30분
        redisRateLimiterService.clock = Clock.fixed(Instant.parse("2026-11-01T12:30:00Z"), ZoneId.of("Asia/Seoul"));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment("gemini:rate:1:daily")).thenReturn(1L);

        redisRateLimiterService.increment(1L);

        verify(redisTemplate).expire("gemini:rate:1:daily", Duration.ofMinutes(150));
        verify(valueOperations, never()).increment("gemini:rate:1:minute");
    }

    @Test
    @DisplayName("시뮬레이터 Rate Limiter - 두 번째 실행부터는 TTL을 다시 걸지 않는다")
    void increment_SecondCall_KeepsTtl() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment("gemini:rate:1:daily")).thenReturn(2L);

        redisRateLimiterService.increment(1L);

        verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("시뮬레이터 Rate Limiter - 오늘 남은 횟수 계산")
    void getRemainingDaily_Success() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("gemini:rate:1:daily")).thenReturn("1");

        int remaining = redisRateLimiterService.getRemainingDaily(1L);

        assertThat(remaining).isEqualTo(2);
    }

    @Test
    @DisplayName("재무설계 Rate Limiter - 오늘 3회째까지는 차감 성공, 첫 차감 때 자정까지 TTL 설정")
    void tryAcquirePlanning_WithinLimit() {
        redisRateLimiterService.clock = Clock.fixed(Instant.parse("2026-11-01T14:59:00Z"), ZoneId.of("Asia/Seoul"));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment("ai_planning:1:daily")).thenReturn(1L, 2L, 3L);

        assertThat(redisRateLimiterService.tryAcquirePlanning(1L)).isTrue();
        assertThat(redisRateLimiterService.tryAcquirePlanning(1L)).isTrue();
        assertThat(redisRateLimiterService.tryAcquirePlanning(1L)).isTrue();

        verify(redisTemplate, times(1)).expire("ai_planning:1:daily", Duration.ofMinutes(1));
        verify(valueOperations, never()).decrement(anyString());
    }

    @Test
    @DisplayName("재무설계 Rate Limiter - 4번째는 거절하고 방금 올린 1을 되돌린다")
    void tryAcquirePlanning_OverLimit_Rollback() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment("ai_planning:1:daily")).thenReturn(4L);
        when(valueOperations.decrement("ai_planning:1:daily")).thenReturn(3L);

        assertThat(redisRateLimiterService.tryAcquirePlanning(1L)).isFalse();

        verify(valueOperations).decrement("ai_planning:1:daily");
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    @DisplayName("재무설계 Rate Limiter - 시뮬레이터와 다른 키를 써서 서로 차감하지 않는다")
    void tryAcquirePlanning_SeparateKeyFromSimulation() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment("ai_planning:1:daily")).thenReturn(2L);

        redisRateLimiterService.tryAcquirePlanning(1L);

        verify(valueOperations, never()).increment("gemini:rate:1:daily");
    }

    @Test
    @DisplayName("재무설계 Rate Limiter - 되돌린 결과가 0 이하면 키를 지워 TTL 없는 카운터가 남지 않게 한다")
    void releasePlanning_DeletesKeyAtZero() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.decrement("ai_planning:1:daily")).thenReturn(0L);

        redisRateLimiterService.releasePlanning(1L);

        verify(redisTemplate).delete("ai_planning:1:daily");
    }
}
