package com.teamfp.aistock.infra.ls;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.teamfp.aistock.infra.ls.dto.LsHogaData;
import com.teamfp.aistock.infra.ls.dto.LsTickData;

/**
 * LS증권 실시간 WebSocket 수신 메시지를 체결/호가로 구분해 파싱하고,
 * {@link LsMarketDataListener} 구현체(4주차 StockBroadcastService)에 전달한다.
 */
@Component
public class LsWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(LsWebSocketHandler.class);
    private static final DateTimeFormatter CHETIME_FORMATTER = DateTimeFormatter.ofPattern("HHmmss");

    private static final String TR_CD_TICK_KOSPI = "S3_";
    private static final String TR_CD_TICK_KOSDAQ = "K3_";
    private static final String TR_CD_HOGA_KOSPI = "H1_";
    private static final String TR_CD_HOGA_KOSDAQ = "HA_";
    private static final int HOGA_LEVEL_COUNT = 10;

    private final List<LsMarketDataListener> listeners;
    private final ObjectMapper objectMapper;
    private final LsReconnectService lsReconnectService;

    public LsWebSocketHandler(List<LsMarketDataListener> listeners, ObjectMapper objectMapper,
                               LsReconnectService lsReconnectService) {
        this.listeners = listeners;
        this.objectMapper = objectMapper;
        this.lsReconnectService = lsReconnectService;
    }

    @Override
    @Async("tickTaskExecutor")
    public void handleTextMessage(WebSocketSession session, TextMessage message) {
        handleMessage(message.getPayload());
    }

    public void handleMessage(String rawMessage) {
        try {
            JsonNode root = objectMapper.readTree(rawMessage);
            JsonNode header = root.path("header");
            String trCd = header.path("tr_cd").asString("");

            // 실시간 등록/해제 요청에 대한 응답은 body 없이 header.rsp_cd/rsp_msg만 온다
            // (성공 시 rsp_cd="00000", 실패 시 그 외 코드 — 둘 다 실측으로 확인됨). 실제 체결/호가
            // 데이터는 이 ACK과 별개로 이후에 body가 채워진 메시지로 도착하므로 여기서는 무시한다.
            String rspCd = header.path("rsp_cd").asString(null);
            if (rspCd != null && !rspCd.isEmpty()) {
                if ("00000".equals(rspCd)) {
                    log.debug("LS 실시간 등록/해제 요청 정상 처리 ACK: trCd={}, rspMsg={}",
                            trCd, header.path("rsp_msg").asString(""));
                } else {
                    log.warn("LS 실시간 등록/해제 요청이 거부됨: trCd={}, rspCd={}, rspMsg={}",
                            trCd, rspCd, header.path("rsp_msg").asString(""));
                }
                return;
            }

            JsonNode body = root.path("body");
            if (body.isMissingNode() || body.isNull()) {
                log.debug("body 없는 메시지 수신, 무시함: trCd={}", trCd);
                return;
            }

            switch (trCd) {
                case TR_CD_TICK_KOSPI, TR_CD_TICK_KOSDAQ -> onTickReceived(parseTick(body));
                case TR_CD_HOGA_KOSPI, TR_CD_HOGA_KOSDAQ -> onHogaReceived(parseHoga(body));
                default -> log.debug("처리 대상이 아닌 tr_cd 수신, 무시함: {}", trCd);
            }
        } catch (Exception e) {
            log.warn("LS 실시간 메시지 파싱 실패, rawMessage={}", rawMessage, e);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.warn("LS WebSocket 연결이 종료됨, status={}", status);
        lsReconnectService.scheduleReconnect();
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("LS WebSocket 전송 오류 발생", exception);
        lsReconnectService.scheduleReconnect();
    }

    private void onTickReceived(LsTickData tickData) {
        listeners.forEach(listener -> listener.onTickReceived(tickData));
    }

    private void onHogaReceived(LsHogaData hogaData) {
        listeners.forEach(listener -> listener.onHogaReceived(hogaData));
    }

    private LsTickData parseTick(JsonNode body) {
        // drate는 API가 이미 부호를 포함해서 보내주므로(하락 시 "-8.17" 등) asDouble()로 그대로
        // 파싱하면 정확하다 — 장중 실측(005930/000660 등 하락 종목 포함)으로 확인됨(NAMING.md 6번
        // 참고). cgubun은 등락구분이 아니라 체결마다 바뀌는 별도 필드라 방향 판단에 쓰면 안 되고,
        // 실제 등락구분 코드는 sign 필드에 있다(2상승/3보합/5하락 실측 확인) — 둘 다 현재는 사용하지 않는다.
        return LsTickData.builder()
                .stockCode(body.path("shcode").asString(null))
                .stockName(null)
                .currentPrice(body.path("price").asLong())
                .changeRate(body.path("drate").asDouble())
                .volume(body.path("volume").asLong())
                .tradedAt(parseTradedAt(body.path("chetime").asString(null)))
                .build();
    }

    private LsHogaData parseHoga(JsonNode body) {
        return LsHogaData.builder()
                .stockCode(body.path("shcode").asString(null))
                .askPrices(readHogaLevels(body, "offerho"))
                .askVolumes(readHogaLevels(body, "offerrem"))
                .bidPrices(readHogaLevels(body, "bidho"))
                .bidVolumes(readHogaLevels(body, "bidrem"))
                .build();
    }

    private List<Long> readHogaLevels(JsonNode body, String fieldPrefix) {
        return java.util.stream.IntStream.rangeClosed(1, HOGA_LEVEL_COUNT)
                .mapToObj(level -> body.path(fieldPrefix + level).asLong())
                .toList();
    }

    private LocalDateTime parseTradedAt(String chetime) {
        if (chetime == null || chetime.isBlank()) {
            return null;
        }
        LocalTime time = LocalTime.parse(chetime, CHETIME_FORMATTER);
        return LocalDateTime.of(java.time.LocalDate.now(), time);
    }
}
