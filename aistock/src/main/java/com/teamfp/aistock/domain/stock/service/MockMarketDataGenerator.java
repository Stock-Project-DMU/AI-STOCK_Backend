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
 * (feature/mock-broadcast, fix/local-market-data-stable 이후 상시 동작).
 *
 * <p>클래스명은 {@code infra/marketdata}의 다른 클라이언트 클래스(외부 시세 데이터 제공사 API 연동
 * 전용)들과 비슷하지만, 이 클래스 자체는 외부 시세 데이터 API를 전혀 호출하지 않고 {@link StockSubscriptionManager}
 * (도메인 서비스)로 "지금 구독 중인 종목이 뭔지"를 조회해야 해서 {@code infra}가 아니라
 * {@code domain/stock/service}에 둔다 — infra는 domain을 직접 참조할 수 없기 때문이다
 * (CLAUDE.md 4번, {@link MarketDataListener} 참고). 반대로 domain이 infra 클라이언트
 * ({@link LocalMarketDataReader})를 주입받아 쓰는 것은 정상적인 방향이다.</p>
 *
 * <p>1초({@code market-data.poll-interval-millis})마다 {@code market_data.json} 전체를 한 번만 읽어
 * ({@link LocalMarketDataReader#getAllCurrentPrices()} 사용 — 구독 종목 수만큼 파일을 반복해서 열지 않기 위함) 지금 구독 중인
 * 종목만 순회하며, 직전에 브로드캐스트한 시점의 {@code updatedAt}과 다를 때만 {@link MarketDataListener}
 * 구현체({@code StockBroadcastService})에 tick/호가를 전달한다. 생성기가 바뀐 내용을 1초마다 저장하므로 화면 반영 지연은 약 1초다
 * (2026-09-30 20초→5초, 2026-10-11 5초→1초). 정규장이 아니면({@link MarketSessionService#isBroadcastWindow()}) 보내지 않는다 —
 * 장외에는 시세 변동이 없어야 하고, 지정가 체결도 이 전송을 계기로만 일어나므로 장외 체결도 함께 막힌다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MockMarketDataGenerator {

    private final LocalMarketDataReader localMarketDataReader;
    // 정규장(+마감 직후 잠깐)에만 실시간 시세를 보낸다 — 장외에는 시세 변동이 없어야 한다(실시세 전환 2단계).
    private final MarketSessionService marketSessionService;
    private final StockSubscriptionManager stockSubscriptionManager;
    private final List<MarketDataListener> listeners;

    // 종목코드별 "마지막으로 브로드캐스트한 market_data.json의 updatedAt". 같은 값이면
    // generator.py가 그 사이 이 종목을 갱신하지 않은 것이므로 중복 브로드캐스트를 생략한다
    // (완전히 새 값이 들어올 때까지는 서버가 재시작돼도 최초 1회는 다시 브로드캐스트된다 —
    // 이 맵이 인메모리라 재시작 시 비워지기 때문이며, 프론트가 페이지 진입 시 REST로 한 번
    // 최신값을 받아오므로 중복 수신되어도 화면에는 영향이 없다).
    private final ConcurrentHashMap<String, LocalDateTime> lastBroadcastedAt = new ConcurrentHashMap<>();

    // 생성기가 바뀐 내용을 1초마다 저장하므로 1초마다 확인한다(이전 5초 고정 — 화면 반영이 최대 5초 늦었다).
    // 실시간 시세 전용 스케줄러(스레드 1개) — 시황 브리핑처럼 오래 걸리는 다른 예약 작업에 막히지 않게 한다(2026-10-12 코드리뷰).
    @Scheduled(fixedDelayString = "${market-data.poll-interval-millis:1000}", scheduler = "marketDataScheduler")
    public void pollAndBroadcast() {
        if (!marketSessionService.isBroadcastWindow()) {
            return;
        }
        Set<String> subscribedStockCodes = stockSubscriptionManager.getActiveSubscribedStockCodes();
        if (subscribedStockCodes.isEmpty()) {
            return;
        }

        Map<String, CurrentPriceDetailDto> currentPrices = localMarketDataReader.getAllCurrentPrices();
        for (String stockCode : subscribedStockCodes) {
            CurrentPriceDetailDto price = currentPrices.get(stockCode);
            if (price == null || price.getCurrentPrice() <= 0 || price.getUpdatedAt() == null
                    || !hasChanged(stockCode, price.getUpdatedAt())) {
                continue;
            }

            broadcastTick(price);
            localMarketDataReader.getHoga(stockCode).ifPresent(this::broadcastHoga);
        }
    }

    // 직전에 보낸 체결보다 "새로운" 것만 보낸다 — 생성기의 updatedAt은 계속 증가하므로 지나간 체결을 다시 보내 화면 가격이 잠깐
    // 뒤로 가는 일을 막는다. 단, 30초 넘게 뒤로 가면 생성기가 재시작했거나 시계가 바뀐 것이라 다시 받는다 — 그렇지 않으면 백엔드를
    // 재시작할 때까지 그 종목 시세가 멈춘다(2026-10-12 코드리뷰).
    static final long CLOCK_RESET_TOLERANCE_SECONDS = 30;

    private boolean hasChanged(String stockCode, LocalDateTime updatedAt) {
        boolean[] isNewer = {false};
        lastBroadcastedAt.compute(stockCode, (code, previous) -> {
            if (previous == null || updatedAt.isAfter(previous)
                    || updatedAt.isBefore(previous.minusSeconds(CLOCK_RESET_TOLERANCE_SECONDS))) {
                isNewer[0] = true;
                return updatedAt;
            }
            return previous;
        });
        return isNewer[0];
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
