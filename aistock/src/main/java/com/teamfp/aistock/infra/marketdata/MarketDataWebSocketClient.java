package com.teamfp.aistock.infra.marketdata;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import tools.jackson.databind.ObjectMapper;

import com.teamfp.aistock.infra.marketdata.dto.MarketDataTokenResponse;

/**
 * 외부 시세 데이터 제공사 실시간시세 WebSocket 연결/구독을 담당하는 infra 클라이언트.
 *
 * 이 클래스는 생성 중에 connect()를 호출하지 않는다. DB PENDING 주문과 구독 카운트가
 * 복원된 뒤 {@link MarketDataStartupService}가 최초 연결을 예약한다.
 *
 * {@code market-data.mode=real}일 때만 빈으로 생성된다. CI/테스트 환경 및 외부 시세 데이터 실연동이 필요 없는
 * 로컬 개발(market-data.mode=mock, application-dev.yml 기본값)에서는 이 빈 자체가 생성되지 않으므로
 * LS_APP_KEY/LS_APP_SECRET 환경변수가 없어도 컨텍스트 로딩이 실패하지 않는다.
 */
@Component
@ConditionalOnProperty(name = "market-data.mode", havingValue = "real")
public class MarketDataWebSocketClient {

    private static final Logger log = LoggerFactory.getLogger(MarketDataWebSocketClient.class);

    private static final String[] REALTIME_TR_CODES = {"S3_", "K3_", "H1_", "HA_"};
    private static final String TR_TYPE_REGISTER = "3";
    private static final String TR_TYPE_UNREGISTER = "4";
    private static final long CONNECT_TIMEOUT_SECONDS = 10L;
    private static final int SEND_TIME_LIMIT_MS = 5_000;
    private static final int SEND_BUFFER_SIZE_LIMIT_BYTES = 512 * 1024;

    private final RestClient restClient;
    private final StandardWebSocketClient webSocketClient = new StandardWebSocketClient();
    private final MarketDataWebSocketHandler marketDataWebSocketHandler;
    private final ObjectMapper objectMapper;
    private final String tokenUrl;
    private final String appKey;
    private final String appSecret;
    private final String websocketUrl;

    private final Set<String> subscribedStockCodes = ConcurrentHashMap.newKeySet();

    // subscribe()/unsubscribe()가 여러 스레드에서 동시에 sendMessage()를 호출할 수 있는데,
    // 표준(JSR-356) WebSocketSession의 sendMessage()는 동시 호출에 대한 스레드 안전성을 보장하지
    // 않는다. Spring이 이런 경우를 위해 제공하는 데코레이터로 감싸서 전송을 직렬화한다.
    private volatile ConcurrentWebSocketSessionDecorator session;
    private volatile String accessToken;

    // disconnect()로 의도적으로 세션을 닫은 경우 afterConnectionClosed()가 재연결을 트리거하면
    // 안 되므로, 종료 사유를 구분하기 위한 플래그. connect() 성공 시 다시 false로 리셋된다.
    private final AtomicBoolean intentionalDisconnect = new AtomicBoolean(false);

    public MarketDataWebSocketClient(RestClient.Builder restClientBuilder,
                              MarketDataWebSocketHandler marketDataWebSocketHandler,
                              ObjectMapper objectMapper,
                              @Value("${market-data.token-url}") String tokenUrl,
                              @Value("${market-data.app-key}") String appKey,
                              @Value("${market-data.app-secret}") String appSecret,
                              @Value("${market-data.websocket-url}") String websocketUrl) {
        this.restClient = restClientBuilder.build();
        this.marketDataWebSocketHandler = marketDataWebSocketHandler;
        this.objectMapper = objectMapper;
        this.tokenUrl = tokenUrl;
        this.appKey = appKey;
        this.appSecret = appSecret;
        this.websocketUrl = websocketUrl;
    }

    public void connect() {
        String token = issueAccessToken();
        try {
            WebSocketSession newSession = webSocketClient.execute(marketDataWebSocketHandler, websocketUrl)
                    .get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            this.session = new ConcurrentWebSocketSessionDecorator(
                    newSession, SEND_TIME_LIMIT_MS, SEND_BUFFER_SIZE_LIMIT_BYTES);
            this.accessToken = token;
            intentionalDisconnect.set(false);
            log.info("외부 시세 데이터 WebSocket 연결 성공: {}", websocketUrl);
            resubscribeAll();
        } catch (Exception e) {
            throw new IllegalStateException("외부 시세 데이터 WebSocket 연결 실패: " + websocketUrl, e);
        }
    }

    public void subscribe(String stockCode) {
        subscribedStockCodes.add(stockCode);
        if (session != null && session.isOpen()) {
            sendRealtimeRegistration(stockCode, TR_TYPE_REGISTER);
        }
    }

    public void unsubscribe(String stockCode) {
        subscribedStockCodes.remove(stockCode);
        if (session != null && session.isOpen()) {
            sendRealtimeRegistration(stockCode, TR_TYPE_UNREGISTER);
        }
    }

    public void disconnect() {
        intentionalDisconnect.set(true);
        ConcurrentWebSocketSessionDecorator currentSession = this.session;
        if (currentSession != null && currentSession.isOpen()) {
            try {
                currentSession.close(CloseStatus.NORMAL);
            } catch (IOException e) {
                log.warn("외부 시세 데이터 WebSocket 연결 종료 중 오류 발생", e);
            }
        }
        session = null;
        subscribedStockCodes.clear();
    }

    /**
     * disconnect() 호출로 인한 의도적 종료인지 여부. MarketDataWebSocketHandler.afterConnectionClosed()가
     * 재연결 트리거 여부를 판단하는 데 사용한다.
     */
    public boolean isIntentionalDisconnect() {
        return intentionalDisconnect.get();
    }

    private String issueAccessToken() {
        String formBody = "grant_type=client_credentials"
                + "&appkey=" + URLEncoder.encode(appKey, StandardCharsets.UTF_8)
                + "&appsecretkey=" + URLEncoder.encode(appSecret, StandardCharsets.UTF_8)
                + "&scope=oob";
        try {
            MarketDataTokenResponse response = restClient.post()
                    .uri(tokenUrl)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(formBody)
                    .retrieve()
                    .body(MarketDataTokenResponse.class);
            if (response == null || response.getAccessToken() == null) {
                throw new IllegalStateException("외부 시세 데이터 토큰 응답에 access_token이 없음");
            }
            return response.getAccessToken();
        } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden e) {
            throw new MarketDataAuthenticationException(
                    "외부 시세 데이터 토큰 발급 인증 실패 (HTTP " + e.getStatusCode().value() + ")", e);
        }
    }

    private void resubscribeAll() {
        subscribedStockCodes.forEach(stockCode -> sendRealtimeRegistration(stockCode, TR_TYPE_REGISTER));
    }

    private void sendRealtimeRegistration(String stockCode, String trType) {
        for (String trCd : REALTIME_TR_CODES) {
            sendMessage(trCd, stockCode, trType);
        }
    }

    private void sendMessage(String trCd, String stockCode, String trType) {
        ConcurrentWebSocketSessionDecorator currentSession = this.session;
        if (currentSession == null || !currentSession.isOpen()) {
            log.warn("외부 시세 데이터 WebSocket 세션이 연결돼있지 않아 등록 메시지를 보낼 수 없음: trCd={}, stockCode={}", trCd, stockCode);
            return;
        }
        try {
            Map<String, Object> header = new LinkedHashMap<>();
            header.put("token", accessToken);
            header.put("tr_type", trType);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("tr_cd", trCd);
            body.put("tr_key", stockCode);
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("header", header);
            message.put("body", body);
            currentSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(message)));
        } catch (IOException e) {
            throw new IllegalStateException("외부 시세 데이터 실시간 등록 메시지 전송 실패: trCd=" + trCd + ", stockCode=" + stockCode, e);
        }
    }
}
