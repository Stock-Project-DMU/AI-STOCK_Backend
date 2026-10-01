package com.teamfp.aistock.infra.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.teamfp.aistock.domain.stock.service.StockSubscriptionManager;

/** 실시간 모드의 전체 빈 그래프가 순환 참조 없이 시작되는지 검증한다. */
@SpringBootTest(properties = "market-data.mode=real")
class RealMarketDataStartupIntegrationTest {
    @Autowired MarketDataWebSocketClient webSocketClient;
    @Autowired StockSubscriptionManager subscriptions;
    @MockitoBean MarketDataReconnectService reconnectService;

    @Test
    void startsRealModeAndSchedulesTheFirstConnection() {
        assertThat(webSocketClient).isNotNull();
        assertThat(subscriptions).isNotNull();
        verify(reconnectService).scheduleReconnect();
    }
}
