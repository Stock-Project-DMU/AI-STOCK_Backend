package com.teamfp.aistock.domain.stock.service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 메인 화면 실시간 갱신(2026-10-13) — 지수는 1초, 종목 순위(정렬 5종, 등록 종목 전체)는 2초마다 STOMP로 보낸다.
 * 예전에는 메인 화면이 10초마다 REST(GET /api/market/rankings?all=true)를 다시 물어 최대 10초 늦게 바뀌었고, 지수는 처음 한 번만 받았다.
 *
 * - 사용자 수와 상관없이 서버가 주기마다 한 번만 계산해 같은 토픽 구독자 모두에게 보낸다(요청 수가 사용자 수만큼 늘지 않음).
 * - 값이 지난번에 보낸 것과 같으면 보내지 않는다.
 * - 정규장과 마감 후 60초에만 보낸다({@link MarketSessionService#isBroadcastWindow()} — 장외에는 가격이 바뀌지 않는다).
 * - 내용은 REST 응답과 같은 DTO라 화면은 받은 목록으로 그대로 바꾸면 된다(GET /api/market/indexes, GET /api/market/rankings?sort=&all=true).
 * - 1초 폴링·스트림 감시와 같은 전용 스케줄러(marketDataScheduler)에서 돌아 시황 브리핑 같은 다른 작업에 막히지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketOverviewBroadcastService {

    public static final String INDEX_TOPIC = "/topic/market/indexes";
    public static final String RANKING_TOPIC_PREFIX = "/topic/market/rankings/";
    static final List<String> RANKING_SORTS = List.of("volume", "value", "rise", "fall", "market-cap");
    // 순위는 지수 2번에 1번(2초) 보낸다 — 등록 종목 전체(105개) 목록이라 지수보다 크다.
    static final int RANKING_EVERY_TICKS = 2;

    private final MarketQueryService marketQueryService;
    private final MarketSessionService marketSessionService;
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    // 토픽별로 마지막에 보낸 내용(JSON) — 같으면 다시 보내지 않는다.
    private final Map<String, String> lastSentByTopic = new ConcurrentHashMap<>();
    private long tickCount;

    @Scheduled(fixedRate = 1000, scheduler = "marketDataScheduler")
    public void broadcast() {
        if (!marketSessionService.isBroadcastWindow()) {
            tickCount = 0;
            return;
        }
        boolean isRankingTick = tickCount++ % RANKING_EVERY_TICKS == 0;
        try {
            sendIfChanged(INDEX_TOPIC, marketQueryService.getIndexes());
            if (isRankingTick) {
                for (String sort : RANKING_SORTS) {
                    sendIfChanged(RANKING_TOPIC_PREFIX + sort, marketQueryService.getRankings(sort, true));
                }
            }
        } catch (RuntimeException e) {
            // 한 번 실패해도 다음 주기에 다시 보낸다(시세 파일을 읽는 도중 등 일시적인 실패).
            log.warn("메인 화면 시세 전송 실패 - 사유: {}", e.getMessage());
        }
    }

    private void sendIfChanged(String topic, List<?> payload) {
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JacksonException e) {
            log.warn("메인 화면 시세 직렬화 실패 - topic: {}, 사유: {}", topic, e.getMessage());
            return;
        }
        if (json.equals(lastSentByTopic.get(topic))) {
            return;
        }
        messagingTemplate.convertAndSend(topic, payload);
        lastSentByTopic.put(topic, json);
    }
}
