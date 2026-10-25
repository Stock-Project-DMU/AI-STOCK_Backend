package com.teamfp.aistock.domain.stock.service;

import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import com.teamfp.aistock.domain.order.service.OrderExecutionService;
import com.teamfp.aistock.domain.stock.dto.HogaDto;
import com.teamfp.aistock.domain.stock.dto.PriceDirection;
import com.teamfp.aistock.domain.stock.dto.StockPriceDto;
import com.teamfp.aistock.domain.stock.dto.response.HogaResponse;
import com.teamfp.aistock.domain.stock.dto.response.StockPriceResponse;
import com.teamfp.aistock.global.redis.RedisStockCacheService;
import com.teamfp.aistock.infra.marketdata.MarketDataListener;
import com.teamfp.aistock.infra.marketdata.dto.HogaData;
import com.teamfp.aistock.infra.marketdata.dto.TickData;

/**
 * 외부 시세 데이터 제공사 실시간 체결/호가를 수신해 Redis 캐싱 + STOMP 브로드캐스팅으로 동시에 처리한다
 * (CLAUDE.md 8번 아키텍처). tick 송신은 MockMarketDataGenerator(5초 주기)와 AsyncConfig
 * 어디에도 구현돼있지 않아(NAMING.md 8-4 참고) 이 서비스에서 종목별로 직접 적용한다 — 체결과
 * 호가는 별도 스트림이라 각각 독립적인 200ms 창을 둔다.
 */
@Slf4j
@Service
public class StockBroadcastService implements MarketDataListener {

    private static final long THROTTLE_INTERVAL_MILLIS = 200L;
    private static final String STOCK_PRICE_TOPIC_PREFIX = "/topic/stock/";
    private static final String STOCK_HOGA_TOPIC_SUFFIX = "/hoga";

    private final RedisStockCacheService redisStockCacheService;
    private final StockNameResolver stockNameResolver;
    private final SimpMessagingTemplate messagingTemplate;
    // 지정가 체결 판단(CLAUDE.md 8번 "tick 수신 시 pending:orders 확인 → 조건 충족 시
    // 낙관적 락으로 체결"). OrderExecutionService 자체는 tick 수신 경로를 모르고 호출만
    // 기다리는 구조라(OrderExecutionService 클래스 상단 Javadoc 참고), 실제 tick 파이프라인인
    // 여기서 종목코드·체결가를 넘겨 호출해야 지정가 주문이 실제로 체결된다.
    // 시세 송신기(MockMarketDataGenerator)가 이 리스너를 먼저 생성할 때 주문 서비스까지 즉시 만들면
    // OrderExecutionService → StockSubscriptionManager로 이어지는 빈 생성 순서가 꼬일 수 있어 지연 주입한다(2026-10-01 순환 의존 대응).
    private final OrderExecutionService orderExecutionService;
    // 지정가 체결 판단(DB 잠금)은 체결 전용 스레드풀로 넘긴다 — 실시간 스트림 수신 스레드가 DB 대기에 막혀 전 종목 시세가 함께 밀리지
    // 않게 한다(2026-10-12 코드리뷰). 시세 캐시·화면 전송은 바로 한다.
    private final Executor tickTaskExecutor;

    public StockBroadcastService(RedisStockCacheService redisStockCacheService, StockNameResolver stockNameResolver,
                                 SimpMessagingTemplate messagingTemplate, @Lazy OrderExecutionService orderExecutionService,
                                 @Qualifier("tickTaskExecutor") Executor tickTaskExecutor) {
        this.redisStockCacheService = redisStockCacheService;
        this.stockNameResolver = stockNameResolver;
        this.messagingTemplate = messagingTemplate;
        this.orderExecutionService = orderExecutionService;
        this.tickTaskExecutor = tickTaskExecutor;
    }

    private final ConcurrentHashMap<String, Long> lastTickProcessedAt = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastHogaProcessedAt = new ConcurrentHashMap<>();

    // 200ms 제한에 걸린 tick·호가는 버리지 않고 종목별 최신 1건만 남겨 창이 끝나면 보낸다(trailing, 2026-10-12 코드리뷰).
    // 실시간 스트림이 0.1초 단위로 오면서 제한에 걸린 마지막 체결이 다음 거래 전까지 화면·지정가 체결에 반영되지 않던 문제를 막는다.
    private final ConcurrentHashMap<String, Pending<TickData>> pendingTicks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Pending<HogaData>> pendingHogas = new ConcurrentHashMap<>();
    // 종목별로 마지막에 보낸 값의 수신 시각 — 늦게 보내는 값이 그사이 이미 나간 더 새 값보다 오래됐으면 버린다(가격이 뒤로 가지 않게).
    private final ConcurrentHashMap<String, Long> lastTickSentReceivedAt = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastHogaSentReceivedAt = new ConcurrentHashMap<>();
    private final ScheduledExecutorService trailingExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "tick-trailing");
        thread.setDaemon(true);
        return thread;
    });

    private record Pending<T>(T data, long receivedAt) {
    }

    @Override
    public void onTickReceived(TickData tickData) {
        processTick(tickData, System.nanoTime());
    }

    private void processTick(TickData tickData, long receivedAt) {
        String stockCode = tickData.getStockCode();
        if (isThrottled(lastTickProcessedAt, stockCode)) {
            scheduleTrailing(pendingTicks, lastTickProcessedAt, stockCode, new Pending<>(tickData, receivedAt), this::flushPendingTick);
            return;
        }
        if (!markSentIfNewer(lastTickSentReceivedAt, stockCode, receivedAt)) {
            return; // 더 새 체결이 이미 나갔다
        }
        pendingTicks.computeIfPresent(stockCode, (code, pending) -> pending.receivedAt() <= receivedAt ? null : pending);

        StockPriceDto dto = toStockPriceDto(tickData);
        redisStockCacheService.saveStockPrice(stockCode, dto);
        broadcastPrice(stockCode, dto);
        long currentPrice = tickData.getCurrentPrice();
        try {
            tickTaskExecutor.execute(() -> {
                try {
                    orderExecutionService.checkAndExecute(stockCode, currentPrice);
                } catch (RuntimeException e) {
                    log.error("지정가 체결 판단 실패 - stockCode: {}, price: {}", stockCode, currentPrice, e);
                }
            });
        } catch (RejectedExecutionException e) {
            // 대기열이 가득 차면 이번 판단은 건너뛴다 — 다음 tick에서 다시 판단한다(checkAndExecute는 멱등).
            log.warn("지정가 체결 판단 대기열 가득 참 - stockCode: {}", stockCode);
        }
    }

    public void broadcastPrice(String stockCode, StockPriceDto dto) {
        messagingTemplate.convertAndSend(STOCK_PRICE_TOPIC_PREFIX + stockCode, StockPriceResponse.from(dto));
    }

    @Override
    public void onHogaReceived(HogaData hogaData) {
        processHoga(hogaData, System.nanoTime());
    }

    private void processHoga(HogaData hogaData, long receivedAt) {
        String stockCode = hogaData.getStockCode();
        if (isThrottled(lastHogaProcessedAt, stockCode)) {
            scheduleTrailing(pendingHogas, lastHogaProcessedAt, stockCode, new Pending<>(hogaData, receivedAt), this::flushPendingHoga);
            return;
        }
        if (!markSentIfNewer(lastHogaSentReceivedAt, stockCode, receivedAt)) {
            return;
        }
        pendingHogas.computeIfPresent(stockCode, (code, pending) -> pending.receivedAt() <= receivedAt ? null : pending);

        HogaDto dto = toHogaDto(hogaData);
        redisStockCacheService.saveHogaData(stockCode, dto);
        broadcastHoga(stockCode, dto);
    }

    // 보내려는 값이 마지막에 보낸 값보다 늦게 받은 것일 때만 true — 같은 종목 안에서 보내는 순서가 받은 순서를 거스르지 않게 한다.
    private static boolean markSentIfNewer(ConcurrentHashMap<String, Long> lastSentReceivedAt, String stockCode, long receivedAt) {
        AtomicBoolean isNewer = new AtomicBoolean(false);
        lastSentReceivedAt.compute(stockCode, (code, last) -> {
            if (last == null || receivedAt >= last) {
                isNewer.set(true);
                return receivedAt;
            }
            return last;
        });
        return isNewer.get();
    }

    // 창 안에서 처음 걸린 것만 예약하고, 이후 것은 최신 값으로 바꿔 두기만 한다 — 창이 끝나는 시점에 마지막 값 1건이 나간다.
    private <T> void scheduleTrailing(ConcurrentHashMap<String, T> pending, ConcurrentHashMap<String, Long> lastProcessedAt,
                                      String stockCode, T latest, Consumer<String> flush) {
        if (pending.put(stockCode, latest) != null) {
            return;
        }
        Long last = lastProcessedAt.get(stockCode);
        long elapsed = last == null ? THROTTLE_INTERVAL_MILLIS : System.currentTimeMillis() - last;
        long delay = Math.max(1L, THROTTLE_INTERVAL_MILLIS - elapsed) + 1L;
        trailingExecutor.schedule(() -> flush.accept(stockCode), delay, TimeUnit.MILLISECONDS);
    }

    // 예약 실행 스레드의 예외는 버려지므로(Future) 여기서 로그로 남긴다 — 지정가 체결 실패 등이 조용히 사라지지 않게.
    private void flushPendingTick(String stockCode) {
        Pending<TickData> pending = pendingTicks.remove(stockCode);
        if (pending == null) {
            return;
        }
        try {
            processTick(pending.data(), pending.receivedAt()); // 아직 창 안이면 다시 예약된다
        } catch (RuntimeException e) {
            log.error("지연 전송 tick 처리 실패 - stockCode: {}", stockCode, e);
        }
    }

    private void flushPendingHoga(String stockCode) {
        Pending<HogaData> pending = pendingHogas.remove(stockCode);
        if (pending == null) {
            return;
        }
        try {
            processHoga(pending.data(), pending.receivedAt());
        } catch (RuntimeException e) {
            log.error("지연 전송 호가 처리 실패 - stockCode: {}", stockCode, e);
        }
    }

    @PreDestroy
    public void shutdownTrailingExecutor() {
        trailingExecutor.shutdownNow();
    }

    public void broadcastHoga(String stockCode, HogaDto dto) {
        messagingTemplate.convertAndSend(
                STOCK_PRICE_TOPIC_PREFIX + stockCode + STOCK_HOGA_TOPIC_SUFFIX, HogaResponse.from(dto));
    }

    private StockPriceDto toStockPriceDto(TickData tickData) {
        String stockCode = tickData.getStockCode();
        String stockName = resolveStockName(stockCode, tickData.getStockName());
        PriceDirection direction = PriceDirection.fromChangeRate(tickData.getChangeRate());
        // 외부 시세 데이터 원본 change 필드는 부호 없는 절대값이라, changeRate로 판단한 방향을 부호로 적용한다.
        long changeAmount = direction == PriceDirection.DOWN ? -tickData.getChangeAmount() : tickData.getChangeAmount();

        return StockPriceDto.builder()
                .stockCode(stockCode)
                .stockName(stockName)
                .currentPrice(tickData.getCurrentPrice())
                .changeAmount(changeAmount)
                .changeRate(tickData.getChangeRate())
                .volume(tickData.getVolume())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private HogaDto toHogaDto(HogaData hogaData) {
        return HogaDto.builder()
                .stockCode(hogaData.getStockCode())
                .askPrices(hogaData.getAskPrices())
                .askVolumes(hogaData.getAskVolumes())
                .bidPrices(hogaData.getBidPrices())
                .bidVolumes(hogaData.getBidVolumes())
                .totalAskVolume(hogaData.getTotalAskVolume())
                .totalBidVolume(hogaData.getTotalBidVolume())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    // TickData.stockName은 항상 null(NAMING.md 6번)이라 StockNameResolver로 우리 DB에서
    // 찾는다 — 어디에도 없으면(KNOWN_ISSUES.md "해소된 이슈" — 종목명 표시) stockCode를 이름 대신 사용한다.
    private String resolveStockName(String stockCode, String tickStockName) {
        if (tickStockName != null) {
            return tickStockName;
        }
        String resolved = stockNameResolver.resolveStockName(stockCode);
        return resolved != null ? resolved : stockCode;
    }

    // get→검사→put이 원자적이지 않으면 같은 종목의 tick 두 개가 서로 다른
    // tickTaskExecutor 워커 스레드에서 동시에 진입해 둘 다 통과할 수 있다(경쟁 상태).
    // compute()는 종목코드(키) 단위로 락을 잡고 원자적으로 실행되므로 get+put을
    // 하나의 원자 연산으로 묶을 수 있다. 단, "반환된 타임스탬프가 내가 넘긴 now와
    // 같은가"로 통과 여부를 판단하면 안 된다 — 여러 스레드가 시스템 클록 해상도
    // 이내(Windows는 약 15ms 단위)에 거의 동시에 진입하면 서로 다른 스레드의 now가
    // 우연히 같은 값이 되어, 실제로는 맵을 갱신하지 않은 스레드도 "통과"로 오판된다
    // (StockBroadcastServiceTest의 동시성 테스트로 재현 확인됨). 그래서 compute()의
    // 리매핑 함수 안에서 "이번 호출이 실제로 맵을 갱신시켰는지"를 AtomicBoolean으로
    // 직접 표시한다. 테스트에서 직접 검증하기 위해 package-private로 둔다.
    boolean isThrottled(ConcurrentHashMap<String, Long> lastProcessedAt, String stockCode) {
        long now = System.currentTimeMillis();
        AtomicBoolean updatedByThisCall = new AtomicBoolean(false);
        lastProcessedAt.compute(stockCode, (code, last) -> {
            if (last != null && now - last < THROTTLE_INTERVAL_MILLIS) {
                return last;
            }
            updatedByThisCall.set(true);
            return now;
        });
        return !updatedByThisCall.get();
    }
}
