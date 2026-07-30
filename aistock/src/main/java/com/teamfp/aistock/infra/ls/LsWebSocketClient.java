package com.teamfp.aistock.infra.ls;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

import tools.jackson.databind.ObjectMapper;

import com.teamfp.aistock.infra.ls.dto.LsTokenResponse;

/**
 * LS증권 실시간시세 WebSocket 연결/구독을 담당하는 infra 클라이언트.
 *
 * 서버 시작 시 자동으로 connect()를 호출하지 않는다 — CLAUDE.md의 "서버 시작 순서: DB PENDING
 * 주문 Redis 재적재 완료 후 LS WebSocket 연결" 규칙에 따라 connect() 호출 시점은 그 재적재를
 * 담당하는 컴포넌트(추후 order 도메인 쪽 구현)가 결정해야 하므로, 이 클래스가 임의로
 * {@code @PostConstruct}로 먼저 연결해버리면 안 된다.
 */
@Component
public class LsWebSocketClient {

    private static final Logger log = LoggerFactory.getLogger(LsWebSocketClient.class);

    private static final String[] REALTIME_TR_CODES = {"S3_", "K3_", "H1_", "HA_"};
    private static final String TR_TYPE_REGISTER = "3";
    private static final String TR_TYPE_UNREGISTER = "4";
    private static final long CONNECT_TIMEOUT_SECONDS = 10L;

    private final RestClient restClient;
    private final StandardWebSocketClient webSocketClient = new StandardWebSocketClient();
    private final LsWebSocketHandler lsWebSocketHandler;
    private final ObjectMapper objectMapper;
    private final String tokenUrl;
    private final String appKey;
    private final String appSecret;
    private final String websocketUrl;

    private final Set<String> subscribedStockCodes = ConcurrentHashMap.newKeySet();
    private volatile WebSocketSession session;
    private volatile String accessToken;

    public LsWebSocketClient(RestClient.Builder restClientBuilder,
                              LsWebSocketHandler lsWebSocketHandler,
                              ObjectMapper objectMapper,
                              @Value("${ls.token-url}") String tokenUrl,
                              @Value("${ls.app-key}") String appKey,
                              @Value("${ls.app-secret}") String appSecret,
                              @Value("${ls.websocket-url}") String websocketUrl) {
        this.restClient = restClientBuilder.build();
        this.lsWebSocketHandler = lsWebSocketHandler;
        this.objectMapper = objectMapper;
        this.tokenUrl = tokenUrl;
        this.appKey = appKey;
        this.appSecret = appSecret;
        this.websocketUrl = websocketUrl;
    }

    public void connect() {
        String token = issueAccessToken();
        try {
            WebSocketSession newSession = webSocketClient.execute(lsWebSocketHandler, websocketUrl)
                    .get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            this.session = newSession;
            this.accessToken = token;
            log.info("LS WebSocket 연결 성공: {}", websocketUrl);
            resubscribeAll();
        } catch (Exception e) {
            throw new IllegalStateException("LS WebSocket 연결 실패: " + websocketUrl, e);
        }
    }

    public void subscribe(String stockCode) {
        subscribedStockCodes.add(stockCode);
        sendRealtimeRegistration(stockCode, TR_TYPE_REGISTER);
    }

    public void unsubscribe(String stockCode) {
        subscribedStockCodes.remove(stockCode);
        sendRealtimeRegistration(stockCode, TR_TYPE_UNREGISTER);
    }

    public void disconnect() {
        WebSocketSession currentSession = this.session;
        if (currentSession != null && currentSession.isOpen()) {
            try {
                currentSession.close(CloseStatus.NORMAL);
            } catch (IOException e) {
                log.warn("LS WebSocket 연결 종료 중 오류 발생", e);
            }
        }
        session = null;
        subscribedStockCodes.clear();
    }

    private String issueAccessToken() {
        String formBody = "grant_type=client_credentials"
                + "&appkey=" + URLEncoder.encode(appKey, StandardCharsets.UTF_8)
                + "&appsecretkey=" + URLEncoder.encode(appSecret, StandardCharsets.UTF_8)
                + "&scope=oob";
        try {
            LsTokenResponse response = restClient.post()
                    .uri(tokenUrl)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(formBody)
                    .retrieve()
                    .body(LsTokenResponse.class);
            if (response == null || response.getAccessToken() == null) {
                throw new IllegalStateException("LS 토큰 응답에 access_token이 없음");
            }
            return response.getAccessToken();
        } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden e) {
            throw new LsAuthenticationException(
                    "LS 토큰 발급 인증 실패 (HTTP " + e.getStatusCode().value() + ")", e);
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
        WebSocketSession currentSession = this.session;
        if (currentSession == null || !currentSession.isOpen()) {
            log.warn("LS WebSocket 세션이 연결돼있지 않아 등록 메시지를 보낼 수 없음: trCd={}, stockCode={}", trCd, stockCode);
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
            throw new IllegalStateException("LS 실시간 등록 메시지 전송 실패: trCd=" + trCd + ", stockCode=" + stockCode, e);
        }
    }
}
