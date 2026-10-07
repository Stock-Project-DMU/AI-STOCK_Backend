package com.teamfp.aistock.domain.stock.service;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.teamfp.aistock.global.redis.RedisPendingOrderService;

import jakarta.annotation.PostConstruct;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 종목별 실시간 시세 구독 여부를 관심종목(watchlist)/상세페이지 조회(viewing)/미체결 지정가 주문(order) 세 출처를
 * 합산한 참조 카운트로 관리한다. 카운트가 1 이상인 종목만 실시간 시세(STOMP)를 보낸다.
 *
 * 단일 서버 운영을 전제로 서버 메모리(ConcurrentHashMap)로 카운트를 관리한다. 다중 서버로
 * 확장되면 Redis 키로 승격이 필요하다(KNOWN_ISSUES.md 3번 참고).
 *
 * 외부 시세 데이터 실시간 구독은 없다(fix/local-market-data-stable에서 real 모드 제거) — 이 카운트는
 * MockMarketDataGenerator가 market_data.json에서 어느 종목을 실시간으로 보낼지 정하는 데 쓰인다.
 *
 * 미체결 주문(order) 출처는 "상세페이지를 떠나도 체결 tick이 끊기지 않게" 하기 위한 것이다 —
 * 주문 한 건당 +1(increaseOrderSubscription), 그 주문이 pending:orders에서 실제로 제거될 때
 * -1(decreaseOrderSubscription)이라, 카운트가 Redis 대기 리스트 건수와 항상 같게 유지된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockSubscriptionManager {

    // 생성자 주입으로 받아 RedisPendingOrderService.initPendingOrders()(@PostConstruct, DB PENDING
    // 주문 재적재)가 이 빈의 restoreOrderSubscriptions()보다 먼저 끝나는 순서를 보장한다.
    private final RedisPendingOrderService redisPendingOrderService;

    private final ConcurrentHashMap<String, AtomicInteger> subscriptionRefCounts = new ConcurrentHashMap<>();

    public void increaseWatchlistSubscription(String stockCode) {
        increase(stockCode);
    }

    public void decreaseWatchlistSubscription(String stockCode) {
        decrease(stockCode);
    }

    public void increaseViewingSubscription(String stockCode) {
        increase(stockCode);
    }

    public void decreaseViewingSubscription(String stockCode) {
        decrease(stockCode);
    }

    /** 지정가 주문이 pending:orders에 올라갈 때 주문 한 건당 호출한다. */
    public void increaseOrderSubscription(String stockCode) {
        increase(stockCode);
    }

    /** 지정가 주문이 체결·취소되어 pending:orders에서 실제로 제거됐을 때 주문 한 건당 호출한다. */
    public void decreaseOrderSubscription(String stockCode) {
        decrease(stockCode);
    }

    /**
     * 서버 기동 시 재적재된 미체결 주문 건수만큼 주문 구독을 복원한다. 서버 메모리의 카운트는
     * 재시작 시 사라지므로, 복원하지 않으면 재시작 전에 걸어둔 지정가 주문은 누군가 그 종목
     * 상세페이지를 열기 전까지 tick을 받지 못해 체결되지 않는다.
     */
    @PostConstruct
    public void restoreOrderSubscriptions() {
        redisPendingOrderService.countPendingOrdersByStockCode().forEach((stockCode, count) -> {
            for (long i = 0; i < count; i++) {
                increaseOrderSubscription(stockCode);
            }
        });
    }

    /**
     * 현재 활성 구독 중인(참조 카운트 > 0) 종목코드 전체를 반환한다. {@code MockMarketDataGenerator}
     * (feature/mock-broadcast)가 5초마다 폴링할 대상을 정하는 데 사용한다 —
     * 아무도 보고 있지 않은 종목까지 매번 브로드캐스트하지 않기 위해서다.
     */
    public Set<String> getActiveSubscribedStockCodes() {
        return subscriptionRefCounts.entrySet().stream()
                .filter(entry -> entry.getValue().get() > 0)
                .map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
    }

    private void increase(String stockCode) {
        AtomicInteger refCount = subscriptionRefCounts.computeIfAbsent(stockCode, code -> new AtomicInteger(0));
        refCount.incrementAndGet();
    }

    private void decrease(String stockCode) {
        AtomicInteger refCount = subscriptionRefCounts.get(stockCode);
        if (refCount == null) {
            return;
        }
        // 맵에서 엔트리를 절대 제거하지 않는다 — 0으로 떨어진 직후 다른 스레드가 같은 stockCode로 increase()를
        // 호출하면 computeIfAbsent가 이 AtomicInteger를 재사용하므로, 지우면 방금 늘린 카운트가 사라지는 경쟁 상태가 생긴다.
        refCount.getAndUpdate(count -> Math.max(0, count - 1));
    }
}
