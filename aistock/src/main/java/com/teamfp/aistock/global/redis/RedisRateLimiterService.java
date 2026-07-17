package com.teamfp.aistock.global.redis;

import java.time.Duration;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * Gemini API 호출 횟수를 제한하는 Rate Limiter.
 *
 * 큐(대기열) 방식 대신 즉시 거절 방식을 선택했다: 큐는 요청을 쌓아두고 순서대로
 * 처리하기 때문에 사용자가 수 분씩 기다릴 수 있는 반면, AI 재무설계·시황 브리핑처럼
 * 즉시 응답이 중요한 기능에서는 한도를 넘으면 바로 429로 안내하는 편이 UX상 낫다.
 *
 * 제한 정책:
 * - 분당 3회: 같은 사용자가 짧은 시간에 연속으로 요청을 몰아 보내는 것을 방지
 * - 일일 10회: 하루 총 사용량 자체를 제한해서 무제한 사용(비용 폭증)을 방지
 * 두 카운터는 서로 독립적으로 관리되며, 둘 중 하나라도 한도를 넘으면 거절된다.
 */
@Service
@RequiredArgsConstructor
public class RedisRateLimiterService {

    private final RedisTemplate<String, String> redisTemplate;

    // 키 형태: gemini:rate:{userId}:minute, gemini:rate:{userId}:daily
    private static final String GEMINI_RATE_KEY = "gemini:rate:";

    private static final int MINUTE_LIMIT = 3;           // 분당 최대 허용 횟수
    private static final long MINUTE_TTL_SECONDS = 60;    // 분당 카운터 TTL (1분)

    private static final int DAILY_LIMIT = 10;            // 일일 최대 허용 횟수
    private static final long DAILY_TTL_SECONDS = 86400;  // 일일 카운터 TTL (24시간)

    /**
     * 해당 사용자가 지금 Gemini API를 호출해도 되는지 확인한다.
     * 실제 호출 카운터를 증가시키지는 않으므로, 호출 가능 여부만 미리 확인하고
     * 싶을 때(예: 버튼 활성화 여부 판단) 부작용 없이 쓸 수 있다.
     * 카운터 키가 아직 없으면(=한 번도 호출 안 함) 0으로 간주한다.
     *
     * @param userId 확인할 사용자 ID
     * @return 분당·일일 한도를 모두 만족하면 true
     */
    public boolean isAllowed(Long userId) {
        String minuteKey = GEMINI_RATE_KEY + userId + ":minute";
        String dailyKey = GEMINI_RATE_KEY + userId + ":daily";

        String minuteCount = redisTemplate.opsForValue().get(minuteKey);
        String dailyCount = redisTemplate.opsForValue().get(dailyKey);

        int minute = minuteCount != null ? Integer.parseInt(minuteCount) : 0;
        int daily = dailyCount != null ? Integer.parseInt(dailyCount) : 0;

        return minute < MINUTE_LIMIT && daily < DAILY_LIMIT;
    }

    /**
     * 실제로 Gemini API를 호출했을 때 호출 카운터를 1씩 증가시킨다.
     * 반드시 {@link #isAllowed(Long)}로 통과 여부를 확인한 "직후"에만 호출해야 하며,
     * 서비스 로직에서는 보통 다음 순서로 쓴다:
     * {@code if (!isAllowed(userId)) throw 429; increment(userId); geminiApiClient.call(...);}
     *
     * INCR로 카운터를 올리고, 그 결과가 정확히 1(=이번이 첫 호출)이면 그때만 TTL을 새로
     * 설정한다. 매번 TTL을 재설정하면 카운터가 절대 만료되지 않는 버그가 생기기 때문에
     * "최초 1회에만 TTL 설정" 방식을 쓴다.
     *
     * @param userId 카운터를 증가시킬 사용자 ID
     */
    public void increment(Long userId) {
        String minuteKey = GEMINI_RATE_KEY + userId + ":minute";
        String dailyKey = GEMINI_RATE_KEY + userId + ":daily";

        // 분당 카운터: 최초 호출(count == 1)일 때만 60초 TTL을 건다.
        Long minuteCount = redisTemplate.opsForValue().increment(minuteKey);
        if (minuteCount != null && minuteCount == 1) {
            redisTemplate.expire(minuteKey, Duration.ofSeconds(MINUTE_TTL_SECONDS));
        }

        // 일일 카운터: 최초 호출(count == 1)일 때만 24시간 TTL을 건다.
        Long dailyCount = redisTemplate.opsForValue().increment(dailyKey);
        if (dailyCount != null && dailyCount == 1) {
            redisTemplate.expire(dailyKey, Duration.ofSeconds(DAILY_TTL_SECONDS));
        }
    }

    /**
     * 오늘 남은 호출 가능 횟수를 계산한다. 프론트에서 "오늘 N회 남음" 같은 안내 문구를
     * 보여줄 때 쓰는 용도이며, 카운터를 증가시키는 부작용은 없다.
     *
     * @param userId 조회할 사용자 ID
     * @return 남은 일일 호출 횟수 (0 미만으로는 내려가지 않음)
     */
    public int getRemainingDaily(Long userId) {
        String dailyKey = GEMINI_RATE_KEY + userId + ":daily";
        String count = redisTemplate.opsForValue().get(dailyKey);
        int used = count != null ? Integer.parseInt(count) : 0;
        return Math.max(0, DAILY_LIMIT - used);
    }
}
