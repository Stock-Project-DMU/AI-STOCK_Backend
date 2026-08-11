package com.teamfp.aistock.domain.stock.service;

import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import com.teamfp.aistock.domain.stock.dto.HogaDto;
import com.teamfp.aistock.domain.stock.dto.PriceDirection;
import com.teamfp.aistock.domain.stock.dto.StockPriceDto;
import com.teamfp.aistock.domain.stock.dto.response.HogaResponse;
import com.teamfp.aistock.domain.stock.dto.response.StockPriceResponse;
import com.teamfp.aistock.global.redis.RedisStockCacheService;
import com.teamfp.aistock.infra.ls.LsMarketDataListener;
import com.teamfp.aistock.infra.ls.dto.LsHogaData;
import com.teamfp.aistock.infra.ls.dto.LsTickData;

import lombok.RequiredArgsConstructor;

/**
 * LS증권 실시간 체결/호가를 수신해 Redis 캐싱 + STOMP 브로드캐스팅으로 동시에 처리한다
 * (CLAUDE.md 8번 아키텍처). tick 자체의 Throttle(200ms)은 LsWebSocketHandler/AsyncConfig
 * 어디에도 구현돼있지 않아(NAMING.md 8-4 참고) 이 서비스에서 종목별로 직접 적용한다 — 체결과
 * 호가는 별도 스트림이라 각각 독립적인 200ms 창을 둔다.
 */
@Service
@RequiredArgsConstructor
public class StockBroadcastService implements LsMarketDataListener {

    private static final long THROTTLE_INTERVAL_MILLIS = 200L;
    private static final String STOCK_PRICE_TOPIC_PREFIX = "/topic/stock/";
    private static final String STOCK_HOGA_TOPIC_SUFFIX = "/hoga";

    private final RedisStockCacheService redisStockCacheService;
    private final StockNameResolver stockNameResolver;
    private final SimpMessagingTemplate messagingTemplate;

    private final ConcurrentHashMap<String, Long> lastTickProcessedAt = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastHogaProcessedAt = new ConcurrentHashMap<>();

    @Override
    public void onTickReceived(LsTickData tickData) {
        String stockCode = tickData.getStockCode();
        if (isThrottled(lastTickProcessedAt, stockCode)) {
            return;
        }

        StockPriceDto dto = toStockPriceDto(tickData);
        redisStockCacheService.saveStockPrice(stockCode, dto);
        broadcastPrice(stockCode, dto);
    }

    public void broadcastPrice(String stockCode, StockPriceDto dto) {
        messagingTemplate.convertAndSend(STOCK_PRICE_TOPIC_PREFIX + stockCode, StockPriceResponse.from(dto));
    }

    @Override
    public void onHogaReceived(LsHogaData hogaData) {
        String stockCode = hogaData.getStockCode();
        if (isThrottled(lastHogaProcessedAt, stockCode)) {
            return;
        }

        HogaDto dto = toHogaDto(hogaData);
        redisStockCacheService.saveHogaData(stockCode, dto);
        broadcastHoga(stockCode, dto);
    }

    public void broadcastHoga(String stockCode, HogaDto dto) {
        messagingTemplate.convertAndSend(
                STOCK_PRICE_TOPIC_PREFIX + stockCode + STOCK_HOGA_TOPIC_SUFFIX, HogaResponse.from(dto));
    }

    private StockPriceDto toStockPriceDto(LsTickData tickData) {
        String stockCode = tickData.getStockCode();
        String stockName = resolveStockName(stockCode, tickData.getStockName());
        PriceDirection direction = PriceDirection.fromChangeRate(tickData.getChangeRate());
        // LS 원본 change 필드는 부호 없는 절대값이라, changeRate로 판단한 방향을 부호로 적용한다.
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

    private HogaDto toHogaDto(LsHogaData hogaData) {
        return HogaDto.builder()
                .stockCode(hogaData.getStockCode())
                .askPrices(hogaData.getAskPrices())
                .askVolumes(hogaData.getAskVolumes())
                .bidPrices(hogaData.getBidPrices())
                .bidVolumes(hogaData.getBidVolumes())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    // LsTickData.stockName은 항상 null(NAMING.md 6번)이라 StockNameResolver로 우리 DB에서
    // 찾는다 — 어디에도 없으면(KNOWN_ISSUES.md 1번) stockCode를 이름 대신 사용한다.
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
