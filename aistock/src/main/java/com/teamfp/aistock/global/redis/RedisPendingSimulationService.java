package com.teamfp.aistock.global.redis;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * 목표 도달 시뮬레이션 실행 결과를 "저장 버튼을 누르기 전까지" 잠깐 보관하는 임시 저장소
 * (feature/goal-simulation-v2, 2026-10-01).
 *
 * 시뮬레이션은 실행할 때마다 DB에 쌓지 않고, 사용자가 저장 버튼을 눌렀을 때만 simulations
 * 테이블에 기록한다. 저장 요청 때 프론트가 결과 전체를 다시 보내게 하면 값 조작이 가능하므로,
 * 서버가 계산한 결과를 여기 보관해두고 저장 요청에는 임시 ID만 받는다. 키에 userId를 넣어
 * 다른 사용자의 임시 결과를 저장할 수 없게 한다. TTL(30분)이 지나면 저장할 수 없고 다시
 * 실행해야 한다.
 *
 * 저장할 때는 값을 지우지 않고 {@link #SAVED_MARKER}로 바꿔 둔다(2026-10-03) — 지워버리면 두 번째 저장
 * 요청이 "만료"와 "이미 저장함"을 구분할 수 없어서다. 표시는 원래 남은 TTL이 지나면 함께 사라진다.
 */
@Service
@RequiredArgsConstructor
public class RedisPendingSimulationService {

    private final RedisTemplate<String, String> redisTemplate;

    // 키 형태: simulation:pending:{userId}:{pendingSimulationId}
    private static final String PENDING_SIMULATION_KEY = "simulation:pending:";

    private static final long TTL_MINUTES = 30;

    /** 이미 저장 처리된 결과 자리에 남겨 두는 값. claimPending()이 이 값을 돌려주면 이미 저장된 결과다. */
    public static final String SAVED_MARKER = "__SAVED__";

    // 값이 있으면 원래 값을 돌려주면서 같은 키를 SAVED_MARKER로 바꾸고 남은 TTL을 유지한다(없으면 nil → null).
    // 이미 SAVED_MARKER면 그대로 돌려준다. 로컬 Redis(6.0 미만)에는 SET KEEPTTL이 없어 PTTL을 읽어 PX로 다시 건다.
    private static final RedisScript<String> CLAIM_SCRIPT = RedisScript.of(
            "local value = redis.call('GET', KEYS[1]) "
                    + "if not value or value == ARGV[1] then return value end "
                    + "local ttl = redis.call('PTTL', KEYS[1]) "
                    + "if ttl and ttl > 0 then redis.call('SET', KEYS[1], ARGV[1], 'PX', ttl) "
                    + "else redis.call('SET', KEYS[1], ARGV[1]) end "
                    + "return value",
            String.class);

    /**
     * 실행 결과(JSON)를 30분 동안 보관한다. 저장 트랜잭션이 롤백됐을 때 원래 결과를 되돌리는 데도 쓴다.
     *
     * @param userId              실행한 사용자 ID
     * @param pendingSimulationId 서버가 발급한 임시 ID(UUID)
     * @param resultJson          저장 시 그대로 DB에 옮길 실행 결과 JSON
     */
    public void savePending(Long userId, String pendingSimulationId, String resultJson) {
        redisTemplate.opsForValue().set(buildKey(userId, pendingSimulationId), resultJson, Duration.ofMinutes(TTL_MINUTES));
    }

    /**
     * 보관 중인 실행 결과를 꺼내면서 동시에 "저장됨" 표시로 바꾼다(GET + SET을 Lua 스크립트 하나로 원자적으로 처리).
     * 저장 버튼을 연달아 눌러 요청이 동시에 두 번 와도 한 요청만 원래 값을 받고, 나머지는 {@link #SAVED_MARKER}를
     * 받는다. 만료됐거나 다른 사용자의 ID면 빈 Optional.
     * Redis 6.2+의 GETDEL·6.0+의 SET KEEPTTL은 로컬 Redis에서 "unknown command"/문법 오류로 실패해 쓰지 않는다.
     */
    public Optional<String> claimPending(Long userId, String pendingSimulationId) {
        return Optional.ofNullable(redisTemplate.execute(CLAIM_SCRIPT,
                List.of(buildKey(userId, pendingSimulationId)), SAVED_MARKER));
    }

    private String buildKey(Long userId, String pendingSimulationId) {
        return PENDING_SIMULATION_KEY + userId + ":" + pendingSimulationId;
    }
}
