# Redis 키 설계 및 로직 (최종본 v3)

**변경사항 v2 → v3**
1. `briefing:{userId}:{date}` 키 제거 (시황 브리핑 → Tavily 즉석 검색으로 변경)
2. `RedisPendingOrderService` 개선 (서버 시작 순서 제어 → WebSocket 연결 전 재적재 완료)
3. `RedisRateLimiterService` 추가 (Gemini API Rate Limiting — Bucket4j 대신 Redis INCR 활용)

**키 네이밍 규칙**: `{서비스}:{목적}:{식별자}`

## TTL 정책 요약 v3 — 8개 키

| 키 | TTL | 용도 |
|---|---|---|
| `auth:refresh:{userId}` | 14일 | Refresh Token 저장 |
| `auth:blacklist:{accessToken}` | Access Token 만료(동적) | 로그아웃 토큰 차단 |
| `auth:email_code:{email}` | 5분 | 이메일 인증 코드 |
| `auth:login_fail:{loginId}` | 10분 | 로그인 실패 횟수 (5회 잠금) |
| `stock:price:{stockCode}` | 5초 | 실시간 주가 캐시 |
| `stock:hoga:{stockCode}` | 2초 | 호가창 데이터 캐시 |
| `pending:orders:{stockCode}` | 없음 | 지정가 미체결 주문 목록 |
| `gemini:rate:{userId}:minute` | 1분 | Gemini API 호출 횟수 (분당 3회 제한) |
| `gemini:rate:{userId}:daily` | 1일 | Gemini API 호출 횟수 (일일 10회 제한) |

**제거된 키**: `briefing:{userId}:{date}` → 시황 브리핑이 Tavily 즉석 검색으로 변경되어 캐싱 불필요

---

## 1. RedisConfig

기존 `new LettuceConnectionFactory(host, port)` 2-argument 생성자는 password, `lettuce.pool.*` 설정을 반영하지 못해 AWS ElastiCache AUTH 환경에서 연결이 실패할 수 있었다.

**수정**: `RedisConnectionFactory`는 별도 커스텀 빈으로 만들지 않고, `application.yml`의 `spring.data.redis.host/port/password/lettuce.pool.*` 값을 Spring Boot Data Redis 오토설정이 그대로 읽어 구성하도록 위임한다. `RedisConfig`에는 `RedisTemplate` 빈만 남긴다.

```java
@Configuration
public class RedisConfig {

    // RedisConnectionFactory는 커스텀 빈으로 정의하지 않는다.
    // spring.data.redis.host / port / password / lettuce.pool.* 값을
    // Spring Boot Data Redis 오토설정이 자동으로 읽어 구성한다.

    @Bean
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new StringRedisSerializer());
        return template;
    }
}
```

---

## 2. RedisTokenService — 인증 토큰 관리

미사용 상수 `ACCESS_TTL_MINUTES` 삭제. `blacklistAccessToken()`은 파라미터로 받은 `remainingMillis`(Access Token의 실제 남은 유효시간)를 그대로 TTL로 사용하므로 고정 상수가 필요 없다.

```java
@Service
@RequiredArgsConstructor
public class RedisTokenService {

    private final RedisTemplate<String, String> redisTemplate;

    private static final String REFRESH_KEY   = "auth:refresh:";
    private static final String BLACKLIST_KEY = "auth:blacklist:";

    private static final long REFRESH_TTL_DAYS = 14;

    // Refresh Token 저장
    public void saveRefreshToken(Long userId, String refreshToken) {
        redisTemplate.opsForValue().set(
            REFRESH_KEY + userId,
            refreshToken,
            Duration.ofDays(REFRESH_TTL_DAYS)
        );
    }

    // Refresh Token 조회
    public String getRefreshToken(Long userId) {
        return redisTemplate.opsForValue().get(REFRESH_KEY + userId);
    }

    // Refresh Token 검증
    public boolean isRefreshTokenValid(Long userId, String refreshToken) {
        String stored = getRefreshToken(userId);
        return stored != null && stored.equals(refreshToken);
    }

    // Refresh Token 삭제 (로그아웃)
    public void deleteRefreshToken(Long userId) {
        redisTemplate.delete(REFRESH_KEY + userId);
    }

    // Access Token 블랙리스트 등록 (로그아웃 시)
    // TTL은 고정값이 아니라 Access Token의 실제 남은 유효시간(remainingMillis)을 사용한다.
    public void blacklistAccessToken(String accessToken, long remainingMillis) {
        redisTemplate.opsForValue().set(
            BLACKLIST_KEY + accessToken,
            "LOGOUT",
            Duration.ofMillis(remainingMillis)
        );
    }

    // 블랙리스트 여부 확인
    public boolean isBlacklisted(String accessToken) {
        return Boolean.TRUE.equals(
            redisTemplate.hasKey(BLACKLIST_KEY + accessToken)
        );
    }
}
```

---

## 3. RedisAuthCodeService — 이메일 인증 / 로그인 실패

```java
@Service
@RequiredArgsConstructor
public class RedisAuthCodeService {

    private final RedisTemplate<String, String> redisTemplate;

    private static final String EMAIL_CODE_KEY = "auth:email_code:";
    private static final String LOGIN_FAIL_KEY = "auth:login_fail:";

    private static final long EMAIL_CODE_TTL_MINUTES = 5;
    private static final long LOGIN_FAIL_TTL_MINUTES = 10;
    private static final int  MAX_LOGIN_FAIL          = 5;

    // 이메일 인증 코드 저장
    public void saveEmailCode(String email, String code) {
        redisTemplate.opsForValue().set(
            EMAIL_CODE_KEY + email,
            code,
            Duration.ofMinutes(EMAIL_CODE_TTL_MINUTES)
        );
    }

    // 이메일 인증 코드 검증 후 삭제
    public boolean verifyAndDeleteEmailCode(String email, String inputCode) {
        String key = EMAIL_CODE_KEY + email;
        String stored = redisTemplate.opsForValue().get(key);
        if (stored != null && stored.equals(inputCode)) {
            redisTemplate.delete(key);
            return true;
        }
        return false;
    }

    // 로그인 실패 횟수 증가
    public long incrementLoginFail(String loginId) {
        String key = LOGIN_FAIL_KEY + loginId;
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1) {
            redisTemplate.expire(key, Duration.ofMinutes(LOGIN_FAIL_TTL_MINUTES));
        }
        return count != null ? count : 1;
    }

    // 로그인 잠금 여부 확인
    public boolean isLoginLocked(String loginId) {
        String count = redisTemplate.opsForValue().get(LOGIN_FAIL_KEY + loginId);
        return count != null && Integer.parseInt(count) >= MAX_LOGIN_FAIL;
    }

    // 로그인 성공 시 실패 카운터 초기화
    public void resetLoginFail(String loginId) {
        redisTemplate.delete(LOGIN_FAIL_KEY + loginId);
    }
}
```

---

## 4. RedisStockCacheService — 실시간 주가/호가 캐시

기존에는 `JsonProcessingException`(checked exception)을 그대로 `throws`로 노출하여, CLAUDE.md의 "예외는 던지고 GlobalExceptionHandler가 처리" 원칙(`CustomException` + `ErrorCode` 체계)과 어긋났다.

**수정**: 메서드 내부에서 `JsonProcessingException`을 catch한 뒤 `CustomException(ErrorCode.REDIS_SERIALIZATION_ERROR)`로 감싸서 unchecked 예외로 던지도록 변경한다.

> `ErrorCode.REDIS_SERIALIZATION_ERROR` 항목은 아직 존재하지 않으며, `feature/common-response` 브랜치에서 `ErrorCode` enum을 만들 때 함께 추가되어야 한다. (해당 enum 파일 자체는 이 문서의 수정 범위가 아니라 설계 계약으로만 명시)

```java
@Service
@RequiredArgsConstructor
public class RedisStockCacheService {

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String STOCK_PRICE_KEY = "stock:price:";
    private static final String STOCK_HOGA_KEY  = "stock:hoga:";

    private static final long PRICE_TTL_SECONDS = 5;  // 주가 5초
    private static final long HOGA_TTL_SECONDS  = 2;  // 호가 2초 (더 빠른 변동)

    // 실시간 주가 저장 (LS증권 WebSocket tick 수신 시 호출)
    public void saveStockPrice(String stockCode, StockPriceDto dto) {
        try {
            redisTemplate.opsForValue().set(
                STOCK_PRICE_KEY + stockCode,
                objectMapper.writeValueAsString(dto),
                Duration.ofSeconds(PRICE_TTL_SECONDS)
            );
        } catch (JsonProcessingException e) {
            throw new CustomException(ErrorCode.REDIS_SERIALIZATION_ERROR, e);
        }
    }

    // 주가 캐시 조회
    public StockPriceDto getStockPrice(String stockCode) {
        String json = redisTemplate.opsForValue().get(STOCK_PRICE_KEY + stockCode);
        if (json == null) return null;
        try {
            return objectMapper.readValue(json, StockPriceDto.class);
        } catch (JsonProcessingException e) {
            throw new CustomException(ErrorCode.REDIS_SERIALIZATION_ERROR, e);
        }
    }

    // 호가창 데이터 저장
    public void saveHogaData(String stockCode, HogaDto dto) {
        try {
            redisTemplate.opsForValue().set(
                STOCK_HOGA_KEY + stockCode,
                objectMapper.writeValueAsString(dto),
                Duration.ofSeconds(HOGA_TTL_SECONDS)
            );
        } catch (JsonProcessingException e) {
            throw new CustomException(ErrorCode.REDIS_SERIALIZATION_ERROR, e);
        }
    }

    // 호가창 데이터 조회
    public HogaDto getHogaData(String stockCode) {
        String json = redisTemplate.opsForValue().get(STOCK_HOGA_KEY + stockCode);
        if (json == null) return null;
        try {
            return objectMapper.readValue(json, HogaDto.class);
        } catch (JsonProcessingException e) {
            throw new CustomException(ErrorCode.REDIS_SERIALIZATION_ERROR, e);
        }
    }
}
```

---

## 5. RedisPendingOrderService — 지정가 미체결 주문

**서버 시작 순서 제어 (핵심)**: 반드시 아래 순서로 실행해야 체결 누락을 방지한다.
1. 서버 시작
2. DB에서 PENDING 주문 전체 조회
3. Redis `pending:orders` 전체 적재 완료
4. 그 다음에 LS증권 WebSocket 연결 시작

순서가 바뀌면 WebSocket tick 수신 시 `pending:orders`가 비어있어 체결 누락이 발생한다.

**배치 동기화 제거 이유**: 체결·취소 시점에 DB와 Redis를 동시 업데이트하는 단일 트랜잭션 방식으로 일관성을 유지하므로 별도 배치가 불필요하다.

**수정 사항**
1. `@Slf4j` 어노테이션 누락 — `log.error`/`log.info` 사용을 위해 추가
2. `initPendingOrders()`의 `redisTemplate.keys(PENDING_KEY + "*")`는 Redis를 블로킹시키는 `KEYS` 명령 안티패턴이므로 `SCAN` 커서 기반으로 교체
3. `JsonProcessingException`을 내부에서 `CustomException(ErrorCode.REDIS_SERIALIZATION_ERROR)`로 감싸서 unchecked로 던지도록 변경 (`ErrorCode.REDIS_SERIALIZATION_ERROR`는 `RedisStockCacheService`와 동일하게 추후 `ErrorCode` enum에 추가 필요)

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisPendingOrderService {

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final OrderRepository orderRepository;

    private static final String PENDING_KEY = "pending:orders:";

    // 서버 시작 시 DB → Redis 재적재 (WebSocket 연결 전 반드시 호출)
    @PostConstruct
    public void initPendingOrders() {
        // 기존 Redis pending:orders 전체 초기화 (SCAN 커서 사용 — KEYS 블로킹 회피)
        Set<String> keys = new HashSet<>();
        try (Cursor<byte[]> cursor = redisTemplate.execute((RedisConnection connection) ->
                connection.scan(ScanOptions.scanOptions().match(PENDING_KEY + "*").count(100).build()))) {
            while (cursor.hasNext()) {
                keys.add(new String(cursor.next()));
            }
        }
        if (!keys.isEmpty()) {
            redisTemplate.delete(keys);
        }

        // DB에서 PENDING 주문 전체 조회 후 재적재
        List<Order> pendingOrders = orderRepository.findAllByStatus(OrderStatus.PENDING);
        for (Order order : pendingOrders) {
            try {
                PendingOrderDto dto = PendingOrderDto.builder()
                    .orderId(order.getOrderId())
                    .userId(order.getAccount().getUser().getUserId())
                    .accountId(order.getAccount().getAccountId())
                    .orderType(order.getOrderType().name())
                    .limitPrice(order.getOrderPrice())
                    .quantity(order.getQuantity())
                    .stockCode(order.getStockCode())
                    .stockName(order.getStockName())
                    .build();
                redisTemplate.opsForList().rightPush(
                    PENDING_KEY + order.getStockCode(),
                    objectMapper.writeValueAsString(dto)
                );
            } catch (JsonProcessingException e) {
                log.error("PENDING 주문 재적재 실패: orderId={}", order.getOrderId(), e);
            }
        }
        log.info("PENDING 주문 재적재 완료: {}건", pendingOrders.size());
    }

    // 지정가 주문 등록 시 Redis에 추가
    public void addPendingOrder(String stockCode, PendingOrderDto order) {
        try {
            redisTemplate.opsForList().rightPush(
                PENDING_KEY + stockCode,
                objectMapper.writeValueAsString(order)
            );
            // TTL 없음 — 체결 또는 취소 시 직접 제거
        } catch (JsonProcessingException e) {
            throw new CustomException(ErrorCode.REDIS_SERIALIZATION_ERROR, e);
        }
    }

    // 해당 종목 미체결 주문 전체 조회 (매 tick 호출)
    public List<PendingOrderDto> getPendingOrders(String stockCode) {
        List<String> jsonList = redisTemplate.opsForList()
            .range(PENDING_KEY + stockCode, 0, -1);
        List<PendingOrderDto> result = new ArrayList<>();
        if (jsonList == null) return result;
        try {
            for (String json : jsonList) {
                result.add(objectMapper.readValue(json, PendingOrderDto.class));
            }
        } catch (JsonProcessingException e) {
            throw new CustomException(ErrorCode.REDIS_SERIALIZATION_ERROR, e);
        }
        return result;
    }

    // 체결 또는 취소된 주문 제거
    public void removePendingOrder(String stockCode, Long orderId) {
        List<String> jsonList = redisTemplate.opsForList()
            .range(PENDING_KEY + stockCode, 0, -1);
        if (jsonList == null) return;
        try {
            for (String json : jsonList) {
                PendingOrderDto order = objectMapper.readValue(json, PendingOrderDto.class);
                if (order.getOrderId().equals(orderId)) {
                    redisTemplate.opsForList().remove(PENDING_KEY + stockCode, 1, json);
                    break;
                }
            }
        } catch (JsonProcessingException e) {
            throw new CustomException(ErrorCode.REDIS_SERIALIZATION_ERROR, e);
        }
    }
}
```

**지정가 체결 조건 체크 (LS증권 tick 수신 시 호출하는 흐름 예시)**

```java
List<PendingOrderDto> pending = getPendingOrders(stockCode);
for (PendingOrderDto order : pending) {
    boolean shouldExecute =
        (order.getType().equals("BUY")  && currentPrice <= order.getLimitPrice()) ||
        (order.getType().equals("SELL") && currentPrice >= order.getLimitPrice());

    if (shouldExecute) {
        orderExecutionService.execute(order, currentPrice);  // 낙관적 락 적용
        removePendingOrder(stockCode, order.getOrderId());
    }
}
```

---

## 6. RedisRateLimiterService — Gemini API Rate Limit (v3 추가)

**큐 방식 대신 Rate Limiter를 선택한 이유**
- 큐: 요청을 쌓아두고 순서대로 처리 → 사용자가 수분씩 대기
- Rate Limiter: 한도 초과 시 즉시 거절 → 사용자에게 즉시 안내

Gemini API는 즉시 응답이 중요한 기능이라 대기열보다 제한 초과 시 즉각 안내가 UX상 더 적합하다.

**제한 정책**: 분당 3회(연속 요청 방지), 일일 10회(무제한 사용 방지)

```java
@Service
@RequiredArgsConstructor
public class RedisRateLimiterService {

    private final RedisTemplate<String, String> redisTemplate;

    private static final String GEMINI_RATE_KEY = "gemini:rate:";   // gemini:rate:{userId}:minute, gemini:rate:{userId}:daily

    private static final int  MINUTE_LIMIT       = 3;   // 분당 최대 3회
    private static final long MINUTE_TTL_SECONDS = 60;  // 1분 TTL

    private static final int  DAILY_LIMIT        = 10;  // 일일 최대 10회
    private static final long DAILY_TTL_SECONDS  = 86400; // 24시간 TTL

    // Gemini API 호출 가능 여부 확인
    public boolean isAllowed(Long userId) {
        String minuteKey = GEMINI_RATE_KEY + userId + ":minute";
        String dailyKey  = GEMINI_RATE_KEY + userId + ":daily";

        String minuteCount = redisTemplate.opsForValue().get(minuteKey);
        String dailyCount  = redisTemplate.opsForValue().get(dailyKey);

        int minute = minuteCount != null ? Integer.parseInt(minuteCount) : 0;
        int daily  = dailyCount  != null ? Integer.parseInt(dailyCount)  : 0;

        return minute < MINUTE_LIMIT && daily < DAILY_LIMIT;
    }

    // Gemini API 호출 시 카운터 증가
    public void increment(Long userId) {
        String minuteKey = GEMINI_RATE_KEY + userId + ":minute";
        String dailyKey  = GEMINI_RATE_KEY + userId + ":daily";

        // 분당 카운터
        Long minuteCount = redisTemplate.opsForValue().increment(minuteKey);
        if (minuteCount != null && minuteCount == 1) {
            redisTemplate.expire(minuteKey, Duration.ofSeconds(MINUTE_TTL_SECONDS));
        }

        // 일일 카운터
        Long dailyCount = redisTemplate.opsForValue().increment(dailyKey);
        if (dailyCount != null && dailyCount == 1) {
            redisTemplate.expire(dailyKey, Duration.ofSeconds(DAILY_TTL_SECONDS));
        }
    }

    // 남은 호출 횟수 조회 (프론트 표시용)
    public int getRemainingDaily(Long userId) {
        String dailyKey = GEMINI_RATE_KEY + userId + ":daily";
        String count = redisTemplate.opsForValue().get(dailyKey);
        int used = count != null ? Integer.parseInt(count) : 0;
        return Math.max(0, DAILY_LIMIT - used);
    }
}
```

**Spring Boot 서비스 레이어 사용 예시**

```java
public AiResponseDto requestAiPlanning(Long userId, String message) {
    if (!rateLimiterService.isAllowed(userId)) {
        throw new TooManyRequestsException(
            "요청 한도를 초과했습니다. 잠시 후 다시 시도해주세요."
        );
    }
    rateLimiterService.increment(userId);
    return geminiService.call(message);
}
```

---

## 7. PendingOrderDto

```java
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PendingOrderDto {
    private Long    orderId;
    private Long    userId;
    private Long    accountId;
    private String  orderType;   // "BUY" or "SELL"
    private Long    limitPrice;  // 지정 가격
    private Integer quantity;    // 주문 수량
    private String  stockCode;
    private String  stockName;
}
```

---

## 8. application.yml — Redis 설정

```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}
      lettuce:
        pool:
          max-active: 10
          max-idle: 5
          min-idle: 1
          max-wait: -1ms
```
