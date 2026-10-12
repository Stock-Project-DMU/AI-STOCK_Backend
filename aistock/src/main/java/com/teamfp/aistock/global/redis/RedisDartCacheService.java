package com.teamfp.aistock.global.redis;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.function.Supplier;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * DART 응답 캐시(2026-10-12 코드리뷰) — 종목 리서치 탭은 로그인 없이 열리고 열 때마다 DART를 불러 하루 호출 한도를 낭비했다.
 * 공시는 분기·연 단위로만 바뀌므로 같은 질문의 답을 Redis에 두고 재사용한다(키 {@code dart:cache:{corpCode}:{종류}[:{연도}]}).
 *
 * <p>만료는 장 일정에 맞춘다 — 공시는 장 시간과 상관없이 평일 낮~저녁에 올라오므로, 기본은 다음 날 장 시작 전({@value #REFRESH_HOUR}시)에
 * 만료되어 하루 동안은 DART를 다시 부르지 않는다. 기간은 호출하는 쪽이 정한다(기업개황 7일, 공시 시즌 실적 1시간 등).
 * Redis가 안 되거나 저장된 값을 읽지 못하면 캐시 없이 DART를 그대로 부른다(캐시 장애가 화면 오류가 되지 않게).
 * 빈 결과(null)는 저장하지 않는다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisDartCacheService {

    private static final String DART_CACHE_KEY = "dart:cache:";
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int REFRESH_HOUR = 8;

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    /** 캐시에 있으면 그 값을, 없으면 {@code loader}로 받아 {@code ttl} 동안 저장한 뒤 돌려준다. */
    public <T> T getOrLoad(String key, Duration ttl, TypeReference<T> type, Supplier<T> loader) {
        String redisKey = DART_CACHE_KEY + key;
        try {
            String cached = redisTemplate.opsForValue().get(redisKey);
            if (cached != null) {
                return objectMapper.readValue(cached, type);
            }
        } catch (RuntimeException e) {
            log.warn("DART 캐시 읽기 실패(DART를 바로 조회) - key: {}, 사유: {}", redisKey, e.getMessage());
        }
        T value = loader.get();
        if (value != null) {
            try {
                redisTemplate.opsForValue().set(redisKey, objectMapper.writeValueAsString(value), ttl);
            } catch (RuntimeException e) {
                log.warn("DART 캐시 저장 실패 - key: {}, 사유: {}", redisKey, e.getMessage());
            }
        }
        return value;
    }

    /** 다음 장 시작 전(한국 시간 {@value #REFRESH_HOUR}:00)까지 남은 시간 — 그 전에는 오늘 08:00, 지났으면 내일 08:00. */
    public static Duration ttlUntilNextMorning() {
        ZonedDateTime now = ZonedDateTime.now(KST);
        ZonedDateTime next = now.with(LocalTime.of(REFRESH_HOUR, 0));
        if (!next.isAfter(now)) {
            next = next.plusDays(1);
        }
        return Duration.between(now, next);
    }
}
