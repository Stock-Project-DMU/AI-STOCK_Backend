package com.teamfp.aistock.global.redis;

import java.util.List;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 관리자 대시보드의 "온라인 사용자 수"를 위한 온라인 사용자 추적 서비스.
 *
 * "로그인된 사용자 중 WebSocket이 실제로 연결되어 있는 사용자"를 의미한다 — JWT 유효성(로그인
 * 여부)만으로는 판단할 수 없고, STOMP CONNECT/DISCONNECT를 실시간으로 추적해야 한다
 * ({@code StompAuthInterceptor} 참고).
 *
 * admin:online:users를 Hash(field=userId, value=그 유저의 활성 WebSocket 세션 수)로 관리한다
 * (코드리뷰 반영, v9 — 원래는 Set이었다). 같은 유저가 탭을 여러 개 열어 세션이 여러 개 생긴
 * 상태에서 그중 하나만 닫혀도 무조건 그 유저를 지워버리면, 나머지 탭이 여전히 연결돼 있는데도
 * 관리자 화면엔 즉시 오프라인으로 보이는 문제가 있었다. Set이 막아주는 건 "같은 유저를 여러 번
 * 세는 중복 카운트" 문제뿐이지, "세션 중 하나만 끊겨도 전체가 꺼지는" 문제는 막지 못한다 —
 * 그래서 세션 수를 세어 0이 될 때만(마지막 세션이 끊겼을 때만) 실제로 오프라인 처리한다.
 * 인원 수 조회는 여전히 HLEN으로 O(1)이다.
 *
 * 고정 TTL을 두지 않고 CONNECT/DISCONNECT 이벤트에 정확히 맞춰 증감시키는 이유는, 접속 시간이
 * 사용자마다 제각각이라 TTL을 걸면 실제로는 끊겼는데 온라인으로 남거나 반대로 연결 중인데
 * 만료되는 문제가 생기기 때문이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisOnlineStatusService {

    private final RedisTemplate<String, String> redisTemplate;

    private static final String ONLINE_KEY = "admin:online:users";

    // removeOnline()용 — 세션 수를 1 줄이고 결과가 0 이하면 그 필드 자체를 지운다. "감소"와
    // "0이면 삭제"를 자바 쪽에서 따로 호출하면, 그 사이에 같은 유저의 다른 세션이 CONNECT로
    // 끼어들 때(증가) 방금 새로 생긴 세션까지 같이 지워버릴 수 있다 — Lua로 원자적으로 묶어야
    // 동시 CONNECT/DISCONNECT 경합에서도 정확하다.
    private static final DefaultRedisScript<Long> DECREMENT_AND_CLEANUP_SCRIPT = new DefaultRedisScript<>("""
            local count = redis.call('HINCRBY', KEYS[1], ARGV[1], -1)
            if count <= 0 then
                redis.call('HDEL', KEYS[1], ARGV[1])
            end
            return count
            """, Long.class);

    /**
     * 서버(재)시작 시 온라인 목록을 통째로 비운다. 서버가 죽으면 그 서버가 들고 있던 모든
     * WebSocket 연결도 함께 끊기므로 이전에 기록된 온라인 사용자는 전부 무효하다.
     * RedisPendingOrderService.initPendingOrders()와 달리 DB에서 재적재할 원본 데이터가 없는
     * 순수 이벤트성 정보라 "재적재"가 아니라 "전체 삭제"가 맞다 — 재시작 후 실제로 재접속하는
     * CONNECT 이벤트가 addOnline()을 다시 호출하며 정상적으로 채워진다.
     */
    @PostConstruct
    public void clearOnlineStatus() {
        redisTemplate.delete(ONLINE_KEY);
        log.info("온라인 사용자 목록 초기화 완료 (서버 재시작)");
    }

    // WebSocket CONNECT 성공(JWT 검증 통과) 시 호출 — 그 유저의 활성 세션 수를 1 늘린다.
    public void addOnline(Long userId) {
        redisTemplate.opsForHash().increment(ONLINE_KEY, String.valueOf(userId), 1);
    }

    /**
     * WebSocket 세션 종료 시 호출 — 활성 세션 수를 1 줄이고, 그게 마지막 세션이었으면(0 이하로
     * 떨어지면) 그 유저를 온라인 목록에서 완전히 제거한다.
     *
     * StompAuthInterceptor는 세션 하나가 끝날 때 이 메서드를 정확히 한 번만 호출해야 한다 —
     * 세션당 두 번 호출되면(예: STOMP DISCONNECT 프레임 처리와 SessionDisconnectEvent 양쪽에서
     * 각각 호출) 실제로는 세션이 하나만 끊겼는데 카운트가 2 줄어들어, 아직 연결돼 있는 다른
     * 세션까지 있는 유저가 조기에 오프라인으로 잘못 표시될 수 있다(구 Set 구현에서는 remove가
     * 멱등해 무해했지만, 세션 수를 세는 이 구현에서는 정확히 한 번만 호출되는 게 중요하다).
     */
    public void removeOnline(Long userId) {
        redisTemplate.execute(DECREMENT_AND_CLEANUP_SCRIPT, List.of(ONLINE_KEY), String.valueOf(userId));
    }

    // 관리자 대시보드 — 현재 온라인 인원 수(활성 세션이 1개 이상인 유저 수)
    public long countOnline() {
        Long size = redisTemplate.opsForHash().size(ONLINE_KEY);
        return size != null ? size : 0;
    }

    public boolean isOnline(Long userId) {
        return Boolean.TRUE.equals(redisTemplate.opsForHash().hasKey(ONLINE_KEY, String.valueOf(userId)));
    }
}
