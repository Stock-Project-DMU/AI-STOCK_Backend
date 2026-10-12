package com.teamfp.aistock.domain.stock.service;

import java.io.IOException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.teamfp.aistock.infra.marketdata.LocalMarketDataReader;
import com.teamfp.aistock.infra.marketdata.MarketDataStreamClient;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 생성기 실시간 스트림 구독자(실시세 전환 7단계) — 앱이 뜨면 전용 스레드에서 {@link MarketDataStreamClient#consume}을 돌려
 * 받은 묶음을 {@link MockMarketDataGenerator#broadcastStreamUpdates}로 넘긴다. 끊기면 1초부터 최대 5초 간격으로 다시 연결한다.
 *
 * <p>스트림은 속도를 높이는 경로일 뿐이다 — 연결이 안 돼도 {@link MockMarketDataGenerator#pollAndBroadcast()}(1초 폴링)가
 * 그대로 실시간 시세를 보낸다. 구독 종목을 알아야 해서 infra가 아니라 domain에 둔다({@link MockMarketDataGenerator}와 같은 이유).</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketDataStreamSubscriber {

    private static final long MIN_RETRY_MILLIS = 1_000L;
    private static final long MAX_RETRY_MILLIS = 5_000L;
    // 생성기 heartbeat가 15초마다 오므로, 45초 동안 아무것도 없으면 끊긴 연결로 본다.
    private static final long IDLE_TIMEOUT_MILLIS = 45_000L;

    private final MarketDataStreamClient marketDataStreamClient;
    private final LocalMarketDataReader localMarketDataReader;
    private final MockMarketDataGenerator mockMarketDataGenerator;

    @Value("${market-data.stream-enabled:true}")
    private boolean streamEnabled = true;

    private volatile boolean running;
    private Thread worker;

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (!streamEnabled || running) {
            return;
        }
        running = true;
        worker = new Thread(this::runLoop, "market-data-stream");
        worker.setDaemon(true);
        worker.start();
        log.info("시세 스트림 구독 시작 - {}", marketDataStreamClient.resolveStreamUrl());
    }

    @PreDestroy
    public synchronized void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
        }
        marketDataStreamClient.closeIfIdle(0);
    }

    @Scheduled(fixedDelay = 10_000L, scheduler = "marketDataScheduler")
    public void checkIdle() {
        if (running) {
            marketDataStreamClient.closeIfIdle(IDLE_TIMEOUT_MILLIS);
        }
    }

    private void runLoop() {
        long retryMillis = MIN_RETRY_MILLIS;
        boolean isDisconnectLogged = false;
        while (running) {
            try {
                // 현재가 조회(REST·주문)에 먼저 반영한 뒤 화면으로 보낸다 — 화면에 보인 가격과 주문 체결가가 어긋나지 않게.
                marketDataStreamClient.consume(events -> {
                    localMarketDataReader.applyStreamQuotes(events);
                    mockMarketDataGenerator.broadcastStreamUpdates(events);
                });
                log.info("시세 스트림 연결 종료 — 다시 연결합니다(그동안 1초 폴링으로 동작)");
                retryMillis = MIN_RETRY_MILLIS;
                isDisconnectLogged = false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (IOException | RuntimeException e) {
                // 생성기가 꺼져 있으면 계속 실패하므로 끊긴 첫 번째만 경고하고 나머지는 debug로 남긴다.
                if (!isDisconnectLogged) {
                    log.warn("시세 스트림 연결 실패(1초 폴링으로 계속 동작) - 사유: {}", describe(e));
                    isDisconnectLogged = true;
                } else {
                    log.debug("시세 스트림 재연결 실패 - 사유: {}", describe(e));
                }
            }
            if (!sleepQuietly(retryMillis)) {
                return;
            }
            retryMillis = Math.min(retryMillis * 2, MAX_RETRY_MILLIS);
        }
    }

    // 연결 거부(ConnectException) 등은 메시지가 비어 있어 예외 이름으로 남긴다.
    private static String describe(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    private boolean sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
            return running;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
