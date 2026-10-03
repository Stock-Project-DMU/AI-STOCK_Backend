package com.teamfp.aistock.global.redis;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * feature/goal-simulation-v2 — 시뮬레이션 임시 보관(simulation:pending:{userId}:{pendingSimulationId}) 단위 테스트.
 * Lua 스크립트 자체(GET 후 SAVED_MARKER로 교체, 남은 TTL 유지)는 로컬 Redis에서 EVAL로 별도 확인했다.
 */
@ExtendWith(MockitoExtension.class)
class RedisPendingSimulationServiceTest {

    @Mock private RedisTemplate<String, String> redisTemplate;
    @Mock private ValueOperations<String, String> values;
    @InjectMocks private RedisPendingSimulationService service;

    @Test
    void savePendingStoresResultForThirtyMinutesUnderUserScopedKey() {
        given(redisTemplate.opsForValue()).willReturn(values);

        service.savePending(7L, "abc", "{\"result\":1}");

        verify(values).set("simulation:pending:7:abc", "{\"result\":1}", Duration.ofMinutes(30));
    }

    @Test
    @SuppressWarnings("unchecked")
    void claimPendingReturnsOriginalValueAndPassesSavedMarker() {
        given(redisTemplate.execute(any(RedisScript.class), anyList(), eq(RedisPendingSimulationService.SAVED_MARKER)))
                .willReturn("{\"result\":1}");

        assertThat(service.claimPending(7L, "abc")).contains("{\"result\":1}");

        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(redisTemplate).execute(any(RedisScript.class), keys.capture(), eq(RedisPendingSimulationService.SAVED_MARKER));
        assertThat(keys.getValue()).containsExactly("simulation:pending:7:abc");
    }

    @Test
    void claimPendingIsEmptyWhenExpired() {
        given(redisTemplate.execute(any(RedisScript.class), anyList(), eq(RedisPendingSimulationService.SAVED_MARKER)))
                .willReturn(null);

        assertThat(service.claimPending(7L, "old")).isEmpty();
    }
}
