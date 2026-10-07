package com.teamfp.aistock.domain.stock.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.teamfp.aistock.infra.marketdata.LocalMarketDataReader;
import com.teamfp.aistock.infra.marketdata.MarketDataListener;
import com.teamfp.aistock.infra.marketdata.dto.CurrentPriceDetailDto;
import com.teamfp.aistock.infra.marketdata.dto.HogaData;
import com.teamfp.aistock.infra.marketdata.dto.TickData;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 실시간 시세 송신기 — local-market-data-generator(별도 Python 프로젝트)가 갱신하는
 * {@code market_data.json}을 폴링해 구독 중인 종목의 tick·호가를 STOMP로 보내는 유일한 실시간 시세 소스다
 * (feature/mock-broadcast, fix/local-market-data-stable에서 외부 시세 데이터 WebSocket(real 모드)을 없앤 뒤 상시 동작).
 *
 * <p>클래스명은 {@code infra/marketdata}의 다른 클라이언트 클래스(외부 시세 데이터 제공사 API 연동
 * 전용)들과 비슷하지만, 이 클래스 자체는 외부 시세 데이터 API를 전혀 호출하지 않고 {@link StockSubscriptionManager}
 * (도메인 서비스)로 "지금 구독 중인 종목이 뭔지"를 조회해야 해서 {@code infra}가 아니라
 * {@code domain/stock/service}에 둔다 — infra는 domain을 직접 참조할 수 없기 때문이다
 * (CLAUDE.md 4번, {@link MarketDataListener} 참고). 반대로 domain이 infra 클라이언트
 * ({@link LocalMarketDataReader})를 주입받아 쓰는 것은 정상적인 방향이다.</p>
 *
 * <p>5초마다 {@code market_data.json} 전체를 한 번만 읽어({@link LocalMarketDataReader#getAllCurrentPrices()}
 * 사용 — 구독 종목 수만큼 파일을 반복해서 열지 않기 위함) 지금 구독 중인 종목만 순회하며,
 * 직전에 브로드캐스트한 시점의 {@code updatedAt}과 다를 때만 {@link MarketDataListener}
 * 구현체({@code StockBroadcastService})에 tick/호가를 전달한다. generator.py의 폴링 주기(기본
 * 10초, {@code local-market-data-generator/.env}의 {@code POLL_INTERVAL_SECONDS})보다 짧게 잡아
 * 화면 반영 지연을 최대 5초로 따라잡는다(실시간 시세 자동 갱신, 2026-09-30 20초→5초).</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MockMarketDataGenerator {

    private static final long POLL_INTERVAL_MILLIS = 5_000L;

    private final LocalMarketDataReader localMarketDataReader;
    private final StockSubscriptionManager stockSubscriptionManager;
    private final List<MarketDataListener> listeners;

    // 종목코드별 "마지막으로 브로드캐스트한 market_data.json의 updatedAt". 같은 값이면
    // generator.py가 그 사이 이 종목을 갱신하지 않은 것이므로 중복 브로드캐스트를 생략한다
    // (완전히 새 값이 들어올 때까지는 서버가 재시작돼도 최초 1회는 다시 브로드캐스트된다 —
    // 이 맵이 인메모리라 재시작 시 비워지기 때문이며, 프론트가 페이지 진입 시 REST로 한 번
    // 최신값을 받아오므로 중복 수신되어도 화면에는 영향이 없다).
    private final ConcurrentHashMap<String, LocalDateTime> lastBroadcastedAt = new ConcurrentHashMap<>();

    @Scheduled(fixedDelay = POLL_INTERVAL_MILLIS)
    public void pollAndBroadcast() {
        Set<String> subscribedStockCodes = stockSubscriptionManager.getActiveSubscribedStockCodes();
        if (subscribedStockCodes.isEmpty()) {
            return;
        }

        Map<String, CurrentPriceDetailDto> currentPrices = localMarketDataReader.getAllCurrentPrices();
        for (String stockCode : subscribedStockCodes) {
            CurrentPriceDetailDto price = currentPrices.get(stockCode);
            if (price == null || price.getUpdatedAt() == null || !hasChanged(stockCode, price.getUpdatedAt())) {
                continue;
            }

            broadcastTick(price);
            localMarketDataReader.getHoga(stockCode).ifPresent(this::broadcastHoga);
        }
    }

    private boolean hasChanged(String stockCode, LocalDateTime updatedAt) {
        LocalDateTime previous = lastBroadcastedAt.put(stockCode, updatedAt);
        return previous == null || !previous.isEqual(updatedAt);
    }

    private void broadcastTick(CurrentPriceDetailDto price) {
        // CurrentPriceDetailDto.changeAmount는 이미 부호가 있는 값(음수=하락)이지만,
        // TickData.changeAmount는 외부 시세 데이터 원본 tick 규약대로 부호 없는 절대값이어야 한다 —
        // StockBroadcastService.onTickReceived()가 changeRate 부호로 다시 부호를 적용하므로,
        // 부호 있는 값을 그대로 넘기면 부호가 이중 반전된다(NAMING.md TickData 항목 참고).
        TickData tickData = TickData.builder()
                .stockCode(price.getStockCode())
                .stockName(price.getStockName())
                .currentPrice(price.getCurrentPrice())
                .changeRate(price.getChangeRate())
                .changeAmount(Math.abs(price.getChangeAmount()))
                .volume(price.getVolume())
                .tradedAt(price.getUpdatedAt())
                .build();
        listeners.forEach(listener -> listener.onTickReceived(tickData));
    }

    private void broadcastHoga(HogaData hoga) {
        listeners.forEach(listener -> listener.onHogaReceived(hoga));
    }
}
