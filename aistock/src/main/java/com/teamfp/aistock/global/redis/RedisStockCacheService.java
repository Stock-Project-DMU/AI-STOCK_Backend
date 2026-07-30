package com.teamfp.aistock.global.redis;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.teamfp.aistock.domain.stock.dto.HogaDto;
import com.teamfp.aistock.domain.stock.dto.StockPriceDto;
import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

/**
 * 실시간 주가·호가 캐시 서비스.
 *
 * LS증권 WebSocket으로 tick(체결/호가)이 들어올 때마다 저장해두고,
 * 조회 API나 STOMP 브로드캐스팅에서는 이 캐시를 먼저 읽어서 매번 LS증권에
 * 재조회하지 않도록 한다. TTL이 아주 짧기 때문에(가격 5초, 호가 2초) 값이
 * 없거나 오래됐으면 그냥 null을 반환하고, 호출하는 쪽(주가 조회 서비스 등)에서
 * 필요하면 LS증권 최신 tick으로 다시 채워질 때까지 기다리거나 REST로 재조회한다.
 */
@Service
@RequiredArgsConstructor
public class RedisStockCacheService {

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper; // DTO <-> JSON 문자열 변환용 (RedisTemplate이 String만 다루므로 필요)

    private static final String STOCK_PRICE_KEY = "stock:price:";
    private static final String STOCK_HOGA_KEY = "stock:hoga:";

    // 주가는 5초, 호가는 2초로 TTL을 다르게 둔다.
    // 호가는 매수/매도 잔량이 주가보다 훨씬 자주 바뀌기 때문에 더 짧게 잡았다.
    private static final long PRICE_TTL_SECONDS = 5;  // 현재가 캐시 TTL (초)
    private static final long HOGA_TTL_SECONDS = 2;   // 호가창 캐시 TTL (초, 변동이 더 잦아서 더 짧게)

    /**
     * 실시간 현재가를 캐시에 저장한다.
     * LS증권 WebSocket에서 체결 tick을 수신할 때마다 호출되는 것을 전제로 한다.
     * 값은 JSON 문자열로 직렬화해서 저장하고, PRICE_TTL_SECONDS가 지나면 Redis가 자동으로 만료시킨다.
     *
     * @param stockCode 종목코드 (예: "005930")
     * @param dto       저장할 현재가 정보
     */
    public void saveStockPrice(String stockCode, StockPriceDto dto) {
        try {
            redisTemplate.opsForValue().set(
                STOCK_PRICE_KEY + stockCode,
                objectMapper.writeValueAsString(dto),
                Duration.ofSeconds(PRICE_TTL_SECONDS)
            );
        } catch (JacksonException e) {
            // 직렬화 실패는 호출 측 로직 문제가 아니라 시스템 내부 오류이므로
            // 체크 예외를 그대로 흘리지 않고 CustomException(500)으로 감싸서 던진다.
            throw new CustomException(ErrorCode.REDIS_SERIALIZATION_ERROR, e);
        }
    }

    /**
     * 캐시된 현재가를 조회한다.
     * TTL이 지나 캐시가 비어있으면(즉 최근 tick이 없었으면) null을 반환한다 — 예외를 던지지 않는다.
     *
     * @param stockCode 종목코드
     * @return 캐시된 현재가 DTO, 캐시가 없으면 null
     */
    public StockPriceDto getStockPrice(String stockCode) {
        String json = redisTemplate.opsForValue().get(STOCK_PRICE_KEY + stockCode);
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, StockPriceDto.class);
        } catch (JacksonException e) {
            throw new CustomException(ErrorCode.REDIS_SERIALIZATION_ERROR, e);
        }
    }

    /**
     * 여러 종목의 현재가를 Redis MGET 한 번으로 조회한다. 마이페이지 수익률/보유종목 화면처럼
     * 보유종목 N개의 시세를 한 번에 보여줘야 하는 화면에서, 종목마다 getStockPrice()를
     * 순차 호출하면 N번의 Redis 왕복이 발생하는 것을 막기 위함이다.
     *
     * 캐시가 비어있는(TTL 만료) 종목은 반환되는 Map에서 키 자체가 빠진다 — 호출부가
     * Map.get(stockCode)가 null인 경우를 직접 처리해야 한다(예: 평단가로 대체).
     *
     * @param stockCodes 조회할 종목코드 목록
     * @return 종목코드 → 현재가 DTO. 캐시 미스 종목은 포함되지 않는다.
     */
    public Map<String, StockPriceDto> getStockPrices(Collection<String> stockCodes) {
        if (stockCodes.isEmpty()) {
            return Map.of();
        }

        List<String> codes = List.copyOf(stockCodes);
        List<String> keys = codes.stream().map(code -> STOCK_PRICE_KEY + code).toList();
        List<String> values = redisTemplate.opsForValue().multiGet(keys);
        // multiGet()은 시그니처상 @Nullable이라 연결 장애 등으로 null을 반환할 가능성이
        // 있다 — 단일 조회 getStockPrice()가 캐시 미스를 null로 처리하는 것과 동일하게,
        // 여기서도 예외 대신 "전부 미스"로 취급해 호출부가 평단가 등으로 대체할 수 있게 한다.
        if (values == null) {
            return Map.of();
        }

        Map<String, StockPriceDto> result = new HashMap<>();
        for (int i = 0; i < codes.size(); i++) {
            String json = values.get(i);
            if (json == null) {
                continue;
            }
            try {
                result.put(codes.get(i), objectMapper.readValue(json, StockPriceDto.class));
            } catch (JacksonException e) {
                throw new CustomException(ErrorCode.REDIS_SERIALIZATION_ERROR, e);
            }
        }
        return result;
    }

    /**
     * 실시간 호가창(매수/매도 5단계) 데이터를 캐시에 저장한다.
     *
     * @param stockCode 종목코드
     * @param dto       저장할 호가 정보
     */
    public void saveHogaData(String stockCode, HogaDto dto) {
        try {
            redisTemplate.opsForValue().set(
                STOCK_HOGA_KEY + stockCode,
                objectMapper.writeValueAsString(dto),
                Duration.ofSeconds(HOGA_TTL_SECONDS)
            );
        } catch (JacksonException e) {
            throw new CustomException(ErrorCode.REDIS_SERIALIZATION_ERROR, e);
        }
    }

    /**
     * 캐시된 호가창 데이터를 조회한다.
     * TTL이 지나 캐시가 비어있으면 null을 반환한다.
     *
     * @param stockCode 종목코드
     * @return 캐시된 호가 DTO, 캐시가 없으면 null
     */
    public HogaDto getHogaData(String stockCode) {
        String json = redisTemplate.opsForValue().get(STOCK_HOGA_KEY + stockCode);
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, HogaDto.class);
        } catch (JacksonException e) {
            throw new CustomException(ErrorCode.REDIS_SERIALIZATION_ERROR, e);
        }
    }
}
