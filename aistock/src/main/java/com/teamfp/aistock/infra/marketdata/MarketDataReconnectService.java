package com.teamfp.aistock.infra.marketdata;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/**
 * 외부 시세 데이터 WebSocket 연결 끊김 감지 시 지수 백오프(1초 → 2초 → 4초 → 최대 30초)로 재연결을 시도한다.
 *
 * {@link MarketDataWebSocketClient}를 {@code @Lazy}로 주입받는 이유: MarketDataWebSocketClient → MarketDataWebSocketHandler
 * → MarketDataReconnectService → MarketDataWebSocketClient로 이어지는 순환 참조를 생성자 주입 그대로 두면 빈 생성 시점에
 * 해결할 수 없다. Lazy 프록시로 실제 빈 생성을 최초 호출 시점까지 미뤄 순환을 끊는다.
 */
@Service
public class MarketDataReconnectService {

    private static final Logger log = LoggerFactory.getLogger(MarketDataReconnectService.class);
    private static final long INITIAL_DELAY_MS = 1_000L;
    private static final long MAX_DELAY_MS = 30_000L;

    private final MarketDataWebSocketClient marketDataWebSocketClient;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "ls-reconnect");
                thread.setDaemon(true);
                return thread;
            });
    private final AtomicBoolean reconnecting = new AtomicBoolean(false);
    private volatile long currentDelayMs = INITIAL_DELAY_MS;

    public MarketDataReconnectService(@Lazy MarketDataWebSocketClient marketDataWebSocketClient) {
        this.marketDataWebSocketClient = marketDataWebSocketClient;
    }

    public void scheduleReconnect() {
        if (reconnecting.compareAndSet(false, true)) {
            currentDelayMs = INITIAL_DELAY_MS;
            log.info("외부 시세 데이터 WebSocket 재연결 예약, {}ms 후 첫 시도", currentDelayMs);
            scheduler.schedule(this::reconnectWithBackoff, currentDelayMs, TimeUnit.MILLISECONDS);
        }
    }

    public void reconnectWithBackoff() {
        try {
            marketDataWebSocketClient.connect();
            reconnecting.set(false);
            currentDelayMs = INITIAL_DELAY_MS;
            log.info("외부 시세 데이터 WebSocket 재연결 성공");
        } catch (MarketDataAuthenticationException e) {
            log.error("외부 시세 데이터 WebSocket 재연결 실패 - 인증 실패로 의심됨 (MARKET_DATA_APP_KEY/MARKET_DATA_APP_SECRET 확인 필요): {}",
                    e.getMessage());
            rescheduleNextAttempt();
        } catch (Exception e) {
            log.warn("외부 시세 데이터 WebSocket 재연결 실패 - 네트워크 문제로 의심됨, {}ms 후 재시도", currentDelayMs, e);
            rescheduleNextAttempt();
        }
    }

    private void rescheduleNextAttempt() {
        currentDelayMs = Math.min(currentDelayMs * 2, MAX_DELAY_MS);
        scheduler.schedule(this::reconnectWithBackoff, currentDelayMs, TimeUnit.MILLISECONDS);
    }
}
