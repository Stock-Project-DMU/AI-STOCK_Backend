package com.teamfp.aistock.global.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.teamfp.aistock.infra.dart.dto.DartDividendResponse;
import com.teamfp.aistock.infra.dart.dto.DartFinancialDetailResponse;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * RedisDartCacheService 검증(2026-10-12 코드리뷰) — 있으면 DART를 부르지 않고, 없으면 받아 저장하며, Redis 장애면 캐시 없이 동작하는지,
 * DART 응답 DTO(레코드)가 저장·복원 후에도 값이 같은지 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class RedisDartCacheServiceTest {

    @Mock private RedisTemplate<String, String> redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;

    private RedisDartCacheService service;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        service = new RedisDartCacheService(redisTemplate, JsonMapper.builder().build());
    }

    private static DartFinancialDetailResponse detail(int year) {
        return new DartFinancialDetailResponse("00126380", year, "11011", "CFS", "20260310002820", false,
                333_605_938_000_000L, null, 43_601_051_000_000L, null, 45_206_805_000_000L, null, null, null, null, null,
                566_942_110_000_000L, 130_621_773_000_000L, null, null, 436_320_337_000_000L, null, null, null, null);
    }

    @Test
    @DisplayName("캐시에 없으면 DART를 불러 TTL과 함께 저장하고, 저장된 JSON을 다시 읽으면 같은 값이다")
    void miss_loadsAndStores_thenHitReturnsSameValue() {
        List<DartFinancialDetailResponse> years = List.of(detail(2025), detail(2024));
        TypeReference<List<DartFinancialDetailResponse>> type = new TypeReference<>() { };
        when(valueOperations.get("dart:cache:00126380:finance:2025")).thenReturn(null);

        List<DartFinancialDetailResponse> loaded = service.getOrLoad("00126380:finance:2025", Duration.ofHours(1), type, () -> years);

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq("dart:cache:00126380:finance:2025"), json.capture(), eq(Duration.ofHours(1)));
        assertThat(loaded).isEqualTo(years);

        // 저장된 JSON으로 다시 조회하면 DART를 부르지 않고 같은 값을 돌려준다
        when(valueOperations.get("dart:cache:00126380:finance:2025")).thenReturn(json.getValue());
        AtomicInteger calls = new AtomicInteger();
        List<DartFinancialDetailResponse> cached = service.getOrLoad("00126380:finance:2025", Duration.ofHours(1), type, () -> {
            calls.incrementAndGet();
            return List.of();
        });
        assertThat(calls).hasValue(0);
        assertThat(cached).isEqualTo(years);
    }

    @Test
    @DisplayName("중첩 레코드(배당 지표)도 저장·복원 후 값이 같다")
    void nestedRecord_roundTrip() {
        DartDividendResponse dividend = new DartDividendResponse("00126380", 2025, "11011", "20260310002820", "2025-12-31",
                List.of(new DartDividendResponse.DividendIndicator("주당 현금배당금(원)", "보통주", 1668.0, 1446.0, 1444.0)));
        TypeReference<DartDividendResponse> type = new TypeReference<>() { };
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        service.getOrLoad("00126380:dividend", Duration.ofHours(1), type, () -> dividend);
        verify(valueOperations).set(anyString(), json.capture(), eq(Duration.ofHours(1)));

        when(valueOperations.get("dart:cache:00126380:dividend")).thenReturn(json.getValue());
        assertThat(service.getOrLoad("00126380:dividend", Duration.ofHours(1), type, () -> null)).isEqualTo(dividend);
    }

    @Test
    @DisplayName("Redis가 안 되면 캐시 없이 DART 값을 그대로 돌려주고, null(빈 결과)은 저장하지 않는다")
    void redisDown_orNull_stillWorks() {
        when(valueOperations.get(anyString())).thenThrow(new IllegalStateException("redis down"));
        assertThat(service.getOrLoad("x:company", Duration.ofDays(7), new TypeReference<String>() { }, () -> "값")).isEqualTo("값");

        org.mockito.Mockito.reset(valueOperations);
        assertThat(service.getOrLoad("y:company", Duration.ofDays(7), new TypeReference<String>() { }, () -> null)).isNull();
        verify(valueOperations, never()).set(anyString(), anyString(), org.mockito.ArgumentMatchers.any(Duration.class));
    }

    @Test
    @DisplayName("기본 만료는 다음 08:00(KST)까지 — 0보다 크고 24시간 이하")
    void ttlUntilNextMorning_withinOneDay() {
        Duration ttl = RedisDartCacheService.ttlUntilNextMorning();
        assertThat(ttl).isPositive().isLessThanOrEqualTo(Duration.ofHours(24));
    }
}
