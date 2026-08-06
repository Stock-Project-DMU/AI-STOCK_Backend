package com.teamfp.aistock.global.stomp;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;
import org.springframework.web.socket.messaging.SessionUnsubscribeEvent;

import com.teamfp.aistock.domain.stock.service.StockSubscriptionManager;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 종목 상세페이지가 STOMP로 /topic/stock/{stockCode}를 구독/해제하는 시점을 감지해
 * StockSubscriptionManager의 조회(viewing) 참조 카운트에 반영한다.
 *
 * 클라이언트가 UNSUBSCRIBE 프레임 없이 비정상 종료되는 경우를 대비해 SessionDisconnectEvent로
 * 보완 처리한다 — CLAUDE.md 8번의 온라인 사용자 추적 정책과 동일한 논리다. 이 클래스가 이
 * 저장소 최초의 STOMP 세션 이벤트 리스너다(KNOWN_ISSUES.md 2번 참고).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockViewSubscriptionListener {

    private static final String STOCK_TOPIC_PREFIX = "/topic/stock/";
    private static final String HOGA_TOPIC_SUFFIX = "/hoga";

    private final StockSubscriptionManager stockSubscriptionManager;

    // sessionId -> (subscriptionId -> stockCode). 세션 종료 시 그 세션이 갖고 있던 구독을
    // 한 번에 정리하기 위해 세션 단위로 묶어서 보관한다.
    private final Map<String, Map<String, String>> sessionSubscriptions = new ConcurrentHashMap<>();

    @EventListener
    public void handleSessionSubscribe(SessionSubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String stockCode = extractStockCode(accessor.getDestination());
        if (stockCode == null) {
            return;
        }

        String sessionId = accessor.getSessionId();
        String subscriptionId = accessor.getSubscriptionId();
        if (sessionId == null || subscriptionId == null) {
            return;
        }

        sessionSubscriptions
                .computeIfAbsent(sessionId, id -> new ConcurrentHashMap<>())
                .put(subscriptionId, stockCode);
        stockSubscriptionManager.increaseViewingSubscription(stockCode);
    }

    @EventListener
    public void handleSessionUnsubscribe(SessionUnsubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = accessor.getSessionId();
        String subscriptionId = accessor.getSubscriptionId();
        if (sessionId == null || subscriptionId == null) {
            return;
        }

        Map<String, String> subscriptions = sessionSubscriptions.get(sessionId);
        if (subscriptions == null) {
            return;
        }
        String stockCode = subscriptions.remove(subscriptionId);
        if (stockCode != null) {
            stockSubscriptionManager.decreaseViewingSubscription(stockCode);
        }
    }

    @EventListener
    public void handleSessionDisconnect(SessionDisconnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = accessor.getSessionId();
        if (sessionId == null) {
            return;
        }

        Map<String, String> subscriptions = sessionSubscriptions.remove(sessionId);
        if (subscriptions == null) {
            return;
        }
        subscriptions.values().forEach(stockSubscriptionManager::decreaseViewingSubscription);
        log.debug("세션 종료로 상세페이지 구독 정리: sessionId={}, stockCount={}", sessionId, subscriptions.size());
    }

    // "/topic/stock/{stockCode}"(가격)와 "/topic/stock/{stockCode}/hoga"(호가) 둘 다
    // 같은 종목의 조회로 취급한다 — 상세페이지가 두 토픽을 동시에 구독해도 참조 카운트가
    // 정확히 대칭으로 증감하므로(각 구독마다 +1, 각 해제마다 -1) 이중 계산 문제는 없다.
    private String extractStockCode(String destination) {
        if (destination == null || !destination.startsWith(STOCK_TOPIC_PREFIX)) {
            return null;
        }
        String remainder = destination.substring(STOCK_TOPIC_PREFIX.length());
        if (remainder.endsWith(HOGA_TOPIC_SUFFIX)) {
            remainder = remainder.substring(0, remainder.length() - HOGA_TOPIC_SUFFIX.length());
        }
        return remainder.isBlank() ? null : remainder;
    }
}
