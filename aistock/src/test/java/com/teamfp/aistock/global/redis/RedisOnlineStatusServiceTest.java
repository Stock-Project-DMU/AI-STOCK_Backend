package com.teamfp.aistock.global.redis;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RedisOnlineStatusService — admin:online:users를 "유저별 활성 세션 수" Hash로 관리하는지
 * 검증한다(코드리뷰 반영 — 원래 Set이었다가 멀티탭 문제로 바뀜, 클래스 Javadoc 참고).
 * removeOnline()의 원자적 감소+정리는 Lua 스크립트(RedisTemplate.execute)로 실행되므로,
 * 스크립트 내부 로직(HINCRBY 후 0 이하면 HDEL) 자체는 실제 Redis가 필요해 여기서 검증하지
 * 않고 — 올바른 키/인자로 스크립트 실행이 요청됐는지만 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class RedisOnlineStatusServiceTest {

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    private RedisOnlineStatusService redisOnlineStatusService;

    private static final String ONLINE_KEY = "admin:online:users";
    private static final Long USER_ID = 1L;

    private void newService() {
        redisOnlineStatusService = new RedisOnlineStatusService(redisTemplate);
    }

    @Test
    @DisplayName("clearOnlineStatus는 서버 재시작 시 온라인 목록 키를 통째로 삭제한다")
    void clearOnlineStatus_deletesKey() {
        newService();

        redisOnlineStatusService.clearOnlineStatus();

        verify(redisTemplate).delete(ONLINE_KEY);
    }

    @Test
    @DisplayName("addOnline은 userId 필드의 세션 카운트를 1 증가시킨다")
    void addOnline_incrementsSessionCount() {
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        newService();

        redisOnlineStatusService.addOnline(USER_ID);

        verify(hashOperations).increment(ONLINE_KEY, "1", 1L);
    }

    @Test
    @DisplayName("removeOnline은 감소+정리 Lua 스크립트를 해당 유저 필드를 인자로 실행한다")
    void removeOnline_executesDecrementScript() {
        newService();

        redisOnlineStatusService.removeOnline(USER_ID);

        verify(redisTemplate).execute(any(), eq(List.of(ONLINE_KEY)), eq("1"));
    }

    @Test
    @DisplayName("countOnline은 Hash 필드 개수(HLEN)를 그대로 반환하고, 키가 없으면 0을 반환한다")
    void countOnline_returnsHashSize() {
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.size(ONLINE_KEY)).thenReturn(3L);
        newService();

        assertThat(redisOnlineStatusService.countOnline()).isEqualTo(3L);
    }

    @Test
    @DisplayName("countOnline은 Hash가 비어 null이 반환되면 0을 반환한다")
    void countOnline_returnsZeroWhenNull() {
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.size(ONLINE_KEY)).thenReturn(null);
        newService();

        assertThat(redisOnlineStatusService.countOnline()).isZero();
    }

    @Test
    @DisplayName("isOnline은 해당 유저 필드 존재 여부를 그대로 반환한다")
    void isOnline_returnsFieldExistence() {
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.hasKey(ONLINE_KEY, "1")).thenReturn(true);
        newService();

        assertThat(redisOnlineStatusService.isOnline(USER_ID)).isTrue();
    }
}
