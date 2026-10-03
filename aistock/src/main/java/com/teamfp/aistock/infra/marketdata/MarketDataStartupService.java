package com.teamfp.aistock.infra.marketdata;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/** DB의 미체결 주문과 구독 카운트가 복원된 뒤 실시간 시세 연결을 시작한다. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "market-data.mode", havingValue = "real")
public class MarketDataStartupService {
    private final MarketDataReconnectService reconnectService;

    @EventListener(ApplicationReadyEvent.class)
    public void connectAfterStartup() {
        reconnectService.scheduleReconnect();
    }
}
