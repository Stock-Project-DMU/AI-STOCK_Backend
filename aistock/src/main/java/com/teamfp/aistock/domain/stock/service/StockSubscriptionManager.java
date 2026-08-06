package com.teamfp.aistock.domain.stock.service;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;

import com.teamfp.aistock.infra.ls.LsWebSocketClient;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 종목별 LS증권 실시간 구독 여부를 관심종목(watchlist)/상세페이지 조회(viewing) 두 출처를
 * 합산한 참조 카운트로 관리한다. 참조 카운트가 0→1이 되는 시점에만 실제로 구독하고,
 * 1→0이 되는 시점에만 구독을 해제한다 — 여러 유저가 같은 종목을 동시에 보고/관심등록해도
 * 중복 구독하지 않기 위함이다.
 *
 * 단일 서버 운영을 전제로 서버 메모리(ConcurrentHashMap)로 카운트를 관리한다. 다중 서버로
 * 확장되면 Redis 키로 승격이 필요하다(KNOWN_ISSUES.md 3번 참고).
 *
 * LsWebSocketClient는 ls.mode=real일 때만 빈으로 존재하므로 Optional로 주입받는다 — mock
 * 모드(Optional.empty())에서는 카운터만 갱신하고 실제 subscribe()/unsubscribe() 호출은
 * debug 로그만 남기고 건너뛴다(KNOWN_ISSUES.md 3번 참고).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockSubscriptionManager {

    // LS증권 소켓당 구독 가능 종목 수 제한. 초과 시 subscribe() 호출은 그대로 진행하되
    // warn 로그만 남긴다 — 본격적인 대응(초과 방지/거부)은 이 브랜치 범위 밖.
    private static final int MAX_SUBSCRIBABLE_STOCK_COUNT = 512;

    private final Optional<LsWebSocketClient> lsWebSocketClient;

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

    private void increase(String stockCode) {
        AtomicInteger refCount = subscriptionRefCounts.computeIfAbsent(stockCode, code -> new AtomicInteger(0));
        if (refCount.incrementAndGet() == 1) {
            subscribe(stockCode);
        }
    }

    private void decrease(String stockCode) {
        AtomicInteger refCount = subscriptionRefCounts.get(stockCode);
        if (refCount == null) {
            return;
        }
        // 맵에서 엔트리를 절대 제거하지 않는다 — 0으로 떨어진 직후 다른 스레드가 같은
        // stockCode로 increase()를 호출하면 computeIfAbsent가 이 AtomicInteger를 재사용하는데,
        // 그 상태에서 엔트리를 remove(key, value)로 지워버리면 방금 재구독한 상태를 지우고
        // unsubscribe()까지 잘못 호출하는 경쟁 상태가 생긴다. previous==1(1→0 전환)일 때만
        // unsubscribe()를 호출해 반복 호출도 막는다.
        int previous = refCount.getAndUpdate(count -> Math.max(0, count - 1));
        if (previous == 1) {
            unsubscribe(stockCode);
        }
    }

    private void subscribe(String stockCode) {
        // 맵 엔트리는 더 이상 제거되지 않으므로(decrease() 참고) size()는 "지금까지 한 번이라도
        // 구독됐던 종목 수"가 되어버려 실제 활성 구독 수와 어긋난다. 활성 구독 수는 refCount > 0인
        // 엔트리만 세어야 정확하다.
        long activeSubscriptionCount = subscriptionRefCounts.values().stream()
                .filter(count -> count.get() > 0)
                .count();
        if (activeSubscriptionCount >= MAX_SUBSCRIBABLE_STOCK_COUNT) {
            log.warn("LS증권 소켓당 구독 가능 종목 수({}개)를 초과할 수 있음: stockCode={}",
                    MAX_SUBSCRIBABLE_STOCK_COUNT, stockCode);
        }
        if (lsWebSocketClient.isEmpty()) {
            log.debug("ls.mode=mock — LS 실시간 구독을 건너뜀: stockCode={}", stockCode);
            return;
        }
        lsWebSocketClient.get().subscribe(stockCode);
    }

    private void unsubscribe(String stockCode) {
        if (lsWebSocketClient.isEmpty()) {
            log.debug("ls.mode=mock — LS 실시간 구독 해제를 건너뜀: stockCode={}", stockCode);
            return;
        }
        lsWebSocketClient.get().unsubscribe(stockCode);
    }
}
