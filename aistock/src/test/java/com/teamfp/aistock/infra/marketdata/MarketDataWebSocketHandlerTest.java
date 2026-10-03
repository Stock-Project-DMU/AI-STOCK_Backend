package com.teamfp.aistock.infra.marketdata;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import tools.jackson.databind.ObjectMapper;

class MarketDataWebSocketHandlerTest {
    @Test
    void serverAccountMismatchStopsTheReconnectLoop() {
        MarketDataWebSocketClient client = mock(MarketDataWebSocketClient.class);
        MarketDataReconnectService reconnect = mock(MarketDataReconnectService.class);
        MarketDataWebSocketHandler handler = new MarketDataWebSocketHandler(
                List.of(), new ObjectMapper(), reconnect, client);

        handler.handleMessage("{\"header\":{\"tr_cd\":\"S3_\",\"rsp_cd\":\"10001\",\"rsp_msg\":\"계좌와 서버 불일치\"}}");
        verify(client).disconnect();

        when(client.isIntentionalDisconnect()).thenReturn(true);
        handler.afterConnectionClosed(mock(WebSocketSession.class), CloseStatus.NORMAL);
        handler.handleTransportError(mock(WebSocketSession.class), new IllegalStateException("closed"));
        verifyNoInteractions(reconnect);
    }
}
