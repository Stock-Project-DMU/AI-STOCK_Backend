package com.teamfp.aistock.global.redis;

import java.time.Duration;
import java.time.LocalDateTime;
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
import com.teamfp.aistock.domain.stock.dto.HogaDto;
import com.teamfp.aistock.domain.stock.dto.StockPriceDto;

import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * RedisPendingOrderService / RedisRateLimiterService / RedisStockCacheService 검증용 임시 테스트.
 * ObjectMapper를 tools.jackson(Jackson 3)으로 교체한 뒤 직렬화/역직렬화가 정상 동작하는지 확인하는 목적.
 * 검증 완료 후 삭제 예정 (임시 테스트 코드).
 */
@ExtendWith(MockitoExtension.class)
class RedisMarketServiceTests {

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

        redisPendingOrderService.removePendingOrder("005930", 1L);

        verify(listOperations).remove("pending:orders:005930", 1, json);
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
    @DisplayName("호가 캐시 저장/조회 - Jackson 3(tools.jackson) 직렬화 정상 동작")
    void saveAndGetHogaData_Success() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        HogaDto dto = HogaDto.builder()
                .stockCode("005930")
                .askPrices(List.of(75100L, 75200L))
                .askVolumes(List.of(100L, 200L))
                .bidPrices(List.of(75000L, 74900L))
                .bidVolumes(List.of(150L, 250L))
                .updatedAt(LocalDateTime.now())
                .build();

        redisStockCacheService.saveHogaData("005930", dto);

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq("stock:hoga:005930"), jsonCaptor.capture(), eq(Duration.ofSeconds(2)));

        when(valueOperations.get("stock:hoga:005930")).thenReturn(jsonCaptor.getValue());
        HogaDto result = redisStockCacheService.getHogaData("005930");

        assertThat(result.getAskPrices()).containsExactly(75100L, 75200L);
        assertThat(result.getBidPrices()).containsExactly(75000L, 74900L);
    }

    @Test
    @DisplayName("Gemini Rate Limiter - 분당/일일 한도 이내면 허용")
    void isAllowed_True() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("gemini:rate:1:minute")).thenReturn("1");
        when(valueOperations.get("gemini:rate:1:daily")).thenReturn("2");

        boolean allowed = redisRateLimiterService.isAllowed(1L);

        assertThat(allowed).isTrue();
    }

    @Test
    @DisplayName("Gemini Rate Limiter - 분당 한도 초과 시 거절")
    void isAllowed_False_MinuteExceeded() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("gemini:rate:1:minute")).thenReturn("3");
        when(valueOperations.get("gemini:rate:1:daily")).thenReturn("2");

        boolean allowed = redisRateLimiterService.isAllowed(1L);

        assertThat(allowed).isFalse();
    }

    @Test
    @DisplayName("Gemini Rate Limiter - 최초 호출 시 카운터 증가 및 TTL 설정")
    void increment_FirstCall_SetsTtl() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment("gemini:rate:1:minute")).thenReturn(1L);
        when(valueOperations.increment("gemini:rate:1:daily")).thenReturn(1L);

        redisRateLimiterService.increment(1L);

        verify(redisTemplate).expire("gemini:rate:1:minute", Duration.ofSeconds(60));
        verify(redisTemplate).expire("gemini:rate:1:daily", Duration.ofSeconds(86400));
    }

    @Test
    @DisplayName("Gemini Rate Limiter - 오늘 남은 횟수 계산")
    void getRemainingDaily_Success() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("gemini:rate:1:daily")).thenReturn("7");

        int remaining = redisRateLimiterService.getRemainingDaily(1L);

        assertThat(remaining).isEqualTo(3);
    }
}
