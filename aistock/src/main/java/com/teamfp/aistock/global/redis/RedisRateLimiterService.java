package com.teamfp.aistock.global.redis;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * AI 기능(목표 도달 시뮬레이션·AI 재무설계)의 계정당 하루 사용 횟수를 제한하는 Rate Limiter.
 *
 * 큐(대기열) 방식 대신 즉시 거절 방식을 선택했다: 큐는 요청을 쌓아두고 순서대로
 * 처리하기 때문에 사용자가 수 분씩 기다릴 수 있는 반면, AI 재무설계·시뮬레이션처럼
 * 즉시 응답이 중요한 기능에서는 한도를 넘으면 바로 429로 안내하는 편이 UX상 낫다.
 *
 * 제한 정책(fix/ai-daily-rate-limit, 2026-11-01):
 * - 시뮬레이터: 하루 3회 (기존 분당 3회 제한은 제거 — 하루 3회 안에서는 의미가 없다)
 * - AI 재무설계: 메시지 전송 하루 5회(2026-11-02 3회→5회) (2026-09-21에 제거했던 제한을 하루 횟수만 다시 적용)
 * 두 카운터는 키가 달라 서로 차감하지 않는다. 하루는 한국 시간 자정에 초기화된다 — 첫 사용 때
 * TTL을 "다음 자정까지 남은 시간"으로 건다(예전처럼 24시간이면 첫 사용 시각에 따라 초기화 시각이 달라진다).
 */
@Service
@RequiredArgsConstructor
public class RedisRateLimiterService {

    private final RedisTemplate<String, String> redisTemplate;

    // 키 형태: gemini:rate:{userId}:daily(시뮬레이터), ai_planning:{userId}:daily(AI 재무설계)
    private static final String GEMINI_RATE_KEY = "gemini:rate:";
    private static final String AI_PLANNING_KEY = "ai_planning:";

    public static final int SIMULATION_DAILY_LIMIT = 3;   // 시뮬레이터 하루 최대 허용 횟수
    public static final int PLANNING_DAILY_LIMIT = 5;     // AI 재무설계 하루 최대 메시지 수

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    // 테스트에서 자정까지 남은 시간(TTL)을 고정값으로 검증할 수 있게 바꿔 끼운다.
    Clock clock = Clock.system(KST);

    /**
     * 해당 사용자가 오늘 시뮬레이터를 더 실행할 수 있는지 확인한다.
     * 실제 호출 카운터를 증가시키지는 않으므로, 호출 가능 여부만 미리 확인하고
     * 싶을 때(예: 버튼 활성화 여부 판단) 부작용 없이 쓸 수 있다.
     * 카운터 키가 아직 없으면(=오늘 한 번도 실행 안 함) 0으로 간주한다.
     *
     * @param userId 확인할 사용자 ID
     * @return 오늘 실행 횟수가 하루 한도 미만이면 true
     */
    public boolean isAllowed(Long userId) {
        return readCount(simulationDailyKey(userId)) < SIMULATION_DAILY_LIMIT;
    }

    /**
     * 시뮬레이터를 실제로 실행했을 때 오늘 카운터를 1 증가시킨다.
     * 반드시 {@link #isAllowed(Long)}로 통과 여부를 확인한 뒤에 호출해야 하며,
     * 서비스 로직에서는 보통 다음 순서로 쓴다:
     * {@code if (!isAllowed(userId)) throw 429; increment(userId); geminiApiClient.call(...);}
     *
     * @param userId 카운터를 증가시킬 사용자 ID
     */
    public void increment(Long userId) {
        incrementDaily(simulationDailyKey(userId));
    }

    /**
     * 오늘 남은 시뮬레이터 실행 가능 횟수를 계산한다. 프론트에서 "오늘 N회 남음" 같은 안내 문구를
     * 보여줄 때 쓰는 용도이며, 카운터를 증가시키는 부작용은 없다.
     *
     * @param userId 조회할 사용자 ID
     * @return 남은 하루 실행 횟수 (0 미만으로는 내려가지 않음)
     */
    public int getRemainingDaily(Long userId) {
        return Math.max(0, SIMULATION_DAILY_LIMIT - readCount(simulationDailyKey(userId)));
    }

    /**
     * AI 재무설계 메시지 1건을 오늘 한도에서 차감한다. 확인과 차감을 INCR 한 번으로 처리해, 한도 직전에
     * 동시에 들어온 요청이 함께 통과하지 못하게 한다(isAllowed → increment처럼 나눠 부르면 둘 다 통과할 수 있다).
     * 한도를 넘었으면 방금 올린 1을 되돌리고 false를 반환한다.
     *
     * @param userId 차감할 사용자 ID
     * @return 차감에 성공했으면(오늘 한도 이내) true
     */
    public boolean tryAcquirePlanning(Long userId) {
        String key = planningDailyKey(userId);
        Long count = incrementDaily(key);
        if (count != null && count > PLANNING_DAILY_LIMIT) {
            decrementDaily(key);
            return false;
        }
        return true;
    }

    /**
     * {@link #tryAcquirePlanning(Long)}로 차감한 1회를 되돌린다. Gemini 오류 등으로 답변을 받지 못한 메시지까지
     * 하루 한도에서 빼지 않기 위해 쓴다.
     *
     * @param userId 되돌릴 사용자 ID
     */
    public void releasePlanning(Long userId) {
        decrementDaily(planningDailyKey(userId));
    }

    private String simulationDailyKey(Long userId) {
        return GEMINI_RATE_KEY + userId + ":daily";
    }

    private String planningDailyKey(Long userId) {
        return AI_PLANNING_KEY + userId + ":daily";
    }

    private int readCount(String key) {
        String count = redisTemplate.opsForValue().get(key);
        return count != null ? Integer.parseInt(count) : 0;
    }

    // INCR로 카운터를 올리고, 그 결과가 정확히 1(=오늘 첫 사용)이면 그때만 다음 자정까지 TTL을 건다.
    // 매번 TTL을 재설정하면 계속 쓰는 사용자의 카운터가 자정에 초기화되지 않는다.
    private Long incrementDaily(String key) {
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1) {
            redisTemplate.expire(key, untilNextMidnight());
        }
        return count;
    }

    // 자정에 키가 만료된 직후 DECR이 오면 TTL 없는 -1 키가 새로 생겨 다음 날부터 카운터가 영영 초기화되지
    // 않는다 — 0 이하가 되면 키를 지워 "오늘 사용 0회"(키 없음) 상태로 되돌린다.
    private void decrementDaily(String key) {
        Long count = redisTemplate.opsForValue().decrement(key);
        if (count != null && count <= 0) {
            redisTemplate.delete(key);
        }
    }

    private Duration untilNextMidnight() {
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(KST));
        ZonedDateTime nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay(KST);
        return Duration.between(now, nextMidnight);
    }
}
