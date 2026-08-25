package com.teamfp.aistock.global.redis;

import java.time.Duration;
import java.util.Optional;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * AI 재무설계 상담(feature/ai-planning)이 DART/네이버 도구를 실행한 결과를 세션 단위로
 * 잠깐 기억해두는 캐시.
 *
 * 같은 세션 안에서 "그 회사 순이익은요?"처럼 같은 회사·같은 조회 조건에 대한 후속 질문이
 * 오면, DART/네이버를 또 호출하지 않고 이전에 가져온 결과를 그대로 재사용한다 — 후속
 * 질문에 대한 응답 속도를 높이고, 외부 API 호출 횟수(특히 Gemini 무료 등급의 낮은 일일
 * 한도)도 아낀다. TTL은 하나의 대화가 이어지는 정도의 시간(30분)으로 짧게 둬서, 그 시간이
 * 지나 자연스럽게 만료되면 다음 조회 때는 다시 최신 데이터를 받아온다.
 */
@Service
@RequiredArgsConstructor
public class RedisAiToolCacheService {

    private final RedisTemplate<String, String> redisTemplate;

    // 키 형태: ai:tool:{sessionId}:{도구이름}?{정렬된 인자 목록}
    private static final String AI_TOOL_CACHE_KEY = "ai:tool:";

    private static final long TTL_MINUTES = 30;

    /**
     * 이전에 캐싱해둔 도구 실행 결과가 있으면 반환한다.
     *
     * @param sessionId 대화 세션 ID
     * @param toolKey   도구 이름 + 인자로 만든 캐시 키(같은 회사·같은 조회 조건이면 동일)
     * @return 캐싱된 결과 문자열. 없으면 빈 Optional
     */
    public Optional<String> getCachedResult(Long sessionId, String toolKey) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(buildKey(sessionId, toolKey)));
    }

    /**
     * 도구 실행 결과를 세션 단위로 캐싱한다. 이미 같은 키로 저장된 값이 있으면 덮어쓰고
     * TTL도 30분으로 다시 시작한다.
     *
     * @param sessionId 대화 세션 ID
     * @param toolKey   도구 이름 + 인자로 만든 캐시 키
     * @param result    캐싱할 도구 실행 결과 문자열
     */
    public void cacheResult(Long sessionId, String toolKey, String result) {
        redisTemplate.opsForValue().set(buildKey(sessionId, toolKey), result, Duration.ofMinutes(TTL_MINUTES));
    }

    private String buildKey(Long sessionId, String toolKey) {
        return AI_TOOL_CACHE_KEY + sessionId + ":" + toolKey;
    }
}
