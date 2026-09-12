# Redis 키 설계 및 로직 (최종본 v8)

## 2026-09-09 이메일 검증 보완

- 인증번호 비교·삭제는 Lua에서 원자적으로 실행한다. 동일 코드 동시 재사용 불가.
- 인증 완료 마커는 GETDEL로 1회 소비한다.
- `auth:login_fail:email:{소문자 이메일}`은 이메일 인증 실패 횟수이며 10분 동안 5회까지 허용한다. 기존 로그인 실패 키 영역을 사용하되 아이디에는 콜론을 허용하지 않는다.
- 이메일 인증 키는 소문자로 정규화한다. 코드 재발송이 실패 횟수를 초기화하지 않는다.

**변경사항 v7 → v8**
1. 키 구조·TTL 변경 없음. AI 상담(`feature/ai-planning`)의 뉴스 검색 백엔드가 Tavily에서
   네이버 뉴스 검색 API(NCP API Hub)로 바뀌면서, `ai:tool:{sessionId}:{도구이름}?{인자}`가
   캐싱하는 도구 실행 결과의 출처만 DART/Tavily → DART/네이버로 바뀌었다. Tavily의
   AI 자동요약(answer)이 동일한 검색어로 다시 호출해도 결과가 재현되지 않는 비결정성
   문제(같은 질문이 10분 사이에 결과가 뒤바뀜, 2026-08-05 라이브 테스트로 확인)가 전환 사유.

**변경사항 v6 → v7**
1. `ai:tool:{sessionId}:{도구이름}?{인자}` 키 추가 (TTL 30분) — `feature/ai-planning`에서
   AI 상담이 DART/Tavily 도구를 실행한 결과를 세션 단위로 캐싱한다. 같은 세션 안에서
   "그 회사 순이익은요?"처럼 같은 회사·같은 조회 조건에 대한 후속 질문이 오면 DART/Tavily를
   다시 호출하지 않고 이전 결과를 그대로 재사용해, 후속 질문 응답 속도를 높이고 외부 API
   호출 횟수(특히 Gemini 무료 등급의 낮은 일일 한도)를 아낀다. `RedisAiToolCacheService`
   신규 추가.

**변경사항 v5 → v6**
1. `auth:email_verified:{email}` 키 추가 (TTL 30분) — `feature/auth-signup`에서 이메일 인증
   (`verifyEmailCode()`)과 회원가입(`signup()`)을 연결하는 1회용 마커. 인증 성공 시
   `RedisAuthCodeService.markEmailVerified()`로 저장하고, `signup()`이 다른 검증(중복 아이디/
   이메일, 관리자 코드)을 다 통과한 뒤 `consumeEmailVerified()`로 확인과 동시에 삭제한다.
   이전에는 `sendEmailCode()`/`verifyEmailCode()` API가 `signup()`과 코드상 전혀 연결돼 있지
   않아, 인증 절차 없이도 `POST /api/auth/signup`을 바로 호출하면 가입이 그대로 성공하는
   문제가 있었다.

**변경사항 v4 → v5**
1. `RedisOnlineStatusService`에 서버 재시작 시 초기화 로직(`clearOnlineStatus`) 추가
   (서버 크래시 시 모든 WebSocket 연결이 함께 끊기는데, 초기화 로직이 없으면
   당시 접속 중이던 사용자가 `admin:online:users`에 영원히 "온라인"으로 남는 문제 발견)

**변경사항 v3 → v4**
1. `admin:online:users` 키 추가 (관리자 대시보드 — 온라인 사용자 수 집계)
2. `RedisOnlineStatusService` 추가 (WebSocket CONNECT/DISCONNECT 시점에 온라인 상태 추적)

**변경사항 v2 → v3**
1. `briefing:{userId}:{date}` 키 제거 (시황 브리핑 → Tavily 즉석 검색으로 변경)
2. `RedisPendingOrderService` 개선 (서버 시작 순서 제어 → WebSocket 연결 전 재적재 완료)
3. `RedisRateLimiterService` 추가 (Gemini API Rate Limiting — Bucket4j 대신 Redis INCR 활용)

**키 네이밍 규칙**: `{서비스}:{목적}:{식별자}`

## TTL 정책 요약 v8 — 11개 키

| 키 | TTL | 용도 |
|---|---|---|
| `auth:refresh:{userId}` | 14일 | Refresh Token 저장 |
| `auth:blacklist:{accessToken}` | Access Token 만료(동적) | 로그아웃 토큰 차단 |
| `auth:email_code:{email}` | 5분 | 이메일 인증 코드 |
| `auth:email_verified:{email}` | 30분 | 이메일 인증 완료 마커 (`signup()`이 소비 후 삭제하는 1회용) |
| `auth:login_fail:{loginId}` | 10분 | 로그인 실패 횟수 (5회 잠금) |
| `stock:price:{stockCode}` | 5초 | 실시간 주가 캐시 |
| `stock:hoga:{stockCode}` | 2초 | 호가창 데이터 캐시 |
| `pending:orders:{stockCode}` | 없음 | 지정가 미체결 주문 목록 |
| `gemini:rate:{userId}:minute` | 1분 | Gemini API 호출 횟수 (분당 3회 제한) |
| `gemini:rate:{userId}:daily` | 1일 | Gemini API 호출 횟수 (일일 10회 제한) |
| `admin:online:users` | 없음 (이벤트 기반) | 현재 WebSocket 연결 중인 userId 집합 (관리자 대시보드) |
| `ai:tool:{sessionId}:{도구이름}?{인자}` | 30분 | AI 상담 세션 내 DART/네이버 도구 실행 결과 캐시 |

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

    private static final String EMAIL_CODE_KEY     = "auth:email_code:";
    private static final String EMAIL_VERIFIED_KEY = "auth:email_verified:";
    private static final String LOGIN_FAIL_KEY     = "auth:login_fail:";

    private static final long EMAIL_CODE_TTL_MINUTES     = 5;
    private static final long EMAIL_VERIFIED_TTL_MINUTES = 30;
    private static final long LOGIN_FAIL_TTL_MINUTES     = 10;
    private static final int  MAX_LOGIN_FAIL             = 5;

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

    // 이메일 인증 성공 마킹 (verifyAndDeleteEmailCode() 성공 직후 호출) — 회원가입 폼 작성
    // 시간을 고려해 인증코드(5분)보다 긴 30분 TTL을 둔다
    public void markEmailVerified(String email) {
        redisTemplate.opsForValue().set(
            EMAIL_VERIFIED_KEY + email,
            "true",
            Duration.ofMinutes(EMAIL_VERIFIED_TTL_MINUTES)
        );
    }

    // 이메일 인증 여부 확인 후 소비(삭제) — 같은 인증을 여러 회원가입에 재사용하지 못하도록 1회용으로 처리
    public boolean consumeEmailVerified(String email) {
        String key = EMAIL_VERIFIED_KEY + email;
        Boolean existed = redisTemplate.hasKey(key);
        if (Boolean.TRUE.equals(existed)) {
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

Spring Boot 4/Jackson 3 기준 `tools.jackson.core.JacksonException`(unchecked)을 그대로 노출하면, CLAUDE.md의 "예외는 던지고 GlobalExceptionHandler가 처리" 원칙(`CustomException` + `ErrorCode` 체계)과 어긋난다.

**수정**: 메서드 내부에서 `JacksonException`을 catch한 뒤 `CustomException(ErrorCode.REDIS_SERIALIZATION_ERROR)`로 감싸서 던지도록 변경한다.

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
        } catch (JacksonException e) {
            throw new CustomException(ErrorCode.REDIS_SERIALIZATION_ERROR, e);
        }
    }

    // 주가 캐시 조회
    public StockPriceDto getStockPrice(String stockCode) {
        String json = redisTemplate.opsForValue().get(STOCK_PRICE_KEY + stockCode);
        if (json == null) return null;
        try {
            return objectMapper.readValue(json, StockPriceDto.class);
        } catch (JacksonException e) {
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
        } catch (JacksonException e) {
            throw new CustomException(ErrorCode.REDIS_SERIALIZATION_ERROR, e);
        }
    }

    // 호가창 데이터 조회
    public HogaDto getHogaData(String stockCode) {
        String json = redisTemplate.opsForValue().get(STOCK_HOGA_KEY + stockCode);
        if (json == null) return null;
        try {
            return objectMapper.readValue(json, HogaDto.class);
        } catch (JacksonException e) {
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
3. `JacksonException`을 내부에서 `CustomException(ErrorCode.REDIS_SERIALIZATION_ERROR)`로 감싸서 unchecked로 던지도록 변경 (`ErrorCode.REDIS_SERIALIZATION_ERROR`는 `RedisStockCacheService`와 동일하게 추후 `ErrorCode` enum에 추가 필요)

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
        // account, account.user를 fetch join으로 함께 로딩한다 — 트랜잭션이 끝난 뒤(@PostConstruct)
        // LAZY 프록시(order.getAccount().getUser())를 건드리면 LazyInitializationException이 나기 때문.
        List<Order> pendingOrders = orderRepository.findAllByStatusWithAccountAndUser(OrderStatus.PENDING);
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
            } catch (JacksonException e) {
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
        } catch (JacksonException e) {
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
        } catch (JacksonException e) {
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
        } catch (JacksonException e) {
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

## 6. RedisRateLimiterService — Gemini API Rate Limit

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

## 7. RedisOnlineStatusService — 온라인 사용자 추적 (v4 신규)

**설계 배경**

관리자 대시보드의 "온라인 사용자 수"는 "로그인된 사용자 중 WebSocket이 실제로 연결되어 있는 사용자"를 의미한다. 로그인 여부(JWT 유효성)만으로는 판단할 수 없고, STOMP 연결 상태를 실시간으로 추적해야 한다.

**Set 자료구조를 선택한 이유**

- 온라인 인원 수 = `SCARD` (O(1))
- 특정 사용자 온라인 여부 = `SISMEMBER` (O(1))
- 중복 연결(같은 유저가 여러 탭에서 접속) 문제 없음 — Set은 자동으로 중복 제거

**TTL을 두지 않고 이벤트 기반으로 관리하는 이유**

접속 시간이 사용자마다 제각각이라 고정 TTL을 걸면 실제로는 연결이 끊겼는데도 온라인으로 남아있거나, 반대로 아직 연결 중인데 만료되는 문제가 생긴다. 대신 `StompAuthInterceptor`의 CONNECT/DISCONNECT 이벤트에 정확히 맞춰 추가·제거한다.

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisOnlineStatusService {

    private final RedisTemplate<String, String> redisTemplate;

    private static final String ONLINE_KEY = "admin:online:users";

    // 서버 시작(재시작) 시 온라인 목록 초기화
    // 서버가 죽으면 그 서버가 들고 있던 모든 WebSocket 연결도 함께 끊기므로,
    // 이전에 기록된 온라인 사용자는 전부 무효하다. RedisPendingOrderService처럼
    // DB에서 재적재할 원본 데이터가 없는 순수 이벤트성 정보이므로 "재적재"가 아니라
    // "전체 삭제"가 정답이다. 재시작 후에는 실제로 재접속하는 CONNECT 이벤트가
    // addOnline()을 다시 호출하며 정상적으로 채워진다.
    @PostConstruct
    public void clearOnlineStatus() {
        redisTemplate.delete(ONLINE_KEY);
        log.info("온라인 사용자 목록 초기화 완료 (서버 재시작)");
    }

    // WebSocket CONNECT 성공 시 호출 (StompAuthInterceptor에서 JWT 검증 통과 후)
    public void addOnline(Long userId) {
        redisTemplate.opsForSet().add(ONLINE_KEY, String.valueOf(userId));
    }

    // WebSocket DISCONNECT 시 호출
    public void removeOnline(Long userId) {
        redisTemplate.opsForSet().remove(ONLINE_KEY, String.valueOf(userId));
    }

    // 현재 온라인 인원 수 (관리자 대시보드용)
    public long countOnline() {
        Long count = redisTemplate.opsForSet().size(ONLINE_KEY);
        return count != null ? count : 0;
    }

    // 특정 사용자 온라인 여부
    public boolean isOnline(Long userId) {
        return Boolean.TRUE.equals(
            redisTemplate.opsForSet().isMember(ONLINE_KEY, String.valueOf(userId))
        );
    }
}
```

**StompAuthInterceptor 연동 지점 (feature/websocket-config, 팀원 B가 2주차에 만든 파일 확장)**

```java
@Override
public Message<?> preSend(Message<?> message, MessageChannel channel) {
    StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

    if (StompCommand.CONNECT.equals(accessor.getCommand())) {
        // 기존 JWT 검증 로직 통과 후
        Long userId = extractUserId(accessor); // JWT에서 userId 추출
        redisOnlineStatusService.addOnline(userId);
    }

    if (StompCommand.DISCONNECT.equals(accessor.getCommand())) {
        Long userId = extractUserId(accessor);
        redisOnlineStatusService.removeOnline(userId);
    }

    return message;
}
```

> DISCONNECT는 클라이언트가 정상 종료하지 않고 네트워크가 끊기는 경우(브라우저 강제 종료 등) STOMP DISCONNECT 프레임 없이 연결만 끊어질 수 있다. 이 경우를 대비해 Spring이 발행하는 `SessionDisconnectEvent`를 `@EventListener`로 별도 처리해 `removeOnline()`을 호출하는 보완 로직이 필요하다. (구현 시 참고)

---

## 8. RedisAiToolCacheService — AI 상담 도구 실행 결과 캐시 (v7 신규, v8에서 뉴스 백엔드 Tavily→네이버 전환)

**설계 배경**

AI 재무설계 상담(`AiPlanningService`)은 사용자 질문마다 Gemini가 필요하다고 판단한
DART/네이버(뉴스 검색, 2026-08-05 이전엔 Tavily) 도구를 실행한다. 그런데 같은 세션 안에서
"그럼 순이익은요?"처럼 방금 조회한 회사에 대한 후속 질문이 오면, 이전과 동일한 회사·동일한
조회 조건으로 DART/네이버를 또 호출하게 된다 — 후속 질문 응답이 느려지고, Gemini 무료
등급의 낮은 일일 호출 한도(실측 하루 30여 회)와 별개로 DART/네이버 자체의 호출 비용도
불필요하게 늘어난다.

**세션 단위로 캐싱하는 이유**

전역 캐싱(`stock:price:{stockCode}`처럼 모든 사용자가 공유)을 하지 않고 세션별로 키를
나눈 이유는, AI 상담 결과는 사용자마다 문맥(투자성향, 보유종목)이 섞여 들어간 조회일 수
있어 다른 세션과 공유하면 안 되기 때문이다. TTL을 30분으로 짧게 둔 이유는 하나의 대화가
이어지는 정도의 시간이면 충분하고, 그 이상 지나면 최신 데이터로 다시 조회하는 게 맞기
때문이다(재무 데이터는 다음 분기·연간 공시로 바뀔 수 있다).

**수정 사항**: 신규 서비스이므로 별도 마이그레이션 없음. `RedisTemplate<String, String>`을
그대로 재사용하는 단순 문자열 캐시라 `ObjectMapper` 직렬화가 필요 없다.

```java
@Service
@RequiredArgsConstructor
public class RedisAiToolCacheService {

    private final RedisTemplate<String, String> redisTemplate;

    // 키 형태: ai:tool:{sessionId}:{도구이름}?{정렬된 인자 목록}
    private static final String AI_TOOL_CACHE_KEY = "ai:tool:";

    private static final long TTL_MINUTES = 30;

    // 이전에 캐싱해둔 도구 실행 결과 조회
    public Optional<String> getCachedResult(Long sessionId, String toolKey) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(buildKey(sessionId, toolKey)));
    }

    // 도구 실행 결과 캐싱 (TTL 30분)
    public void cacheResult(Long sessionId, String toolKey, String result) {
        redisTemplate.opsForValue().set(buildKey(sessionId, toolKey), result, Duration.ofMinutes(TTL_MINUTES));
    }

    private String buildKey(Long sessionId, String toolKey) {
        return AI_TOOL_CACHE_KEY + sessionId + ":" + toolKey;
    }
}
```

**`AiPlanningService` 연동 지점**: `executeTool(Long sessionId, FunctionCall functionCall)`이
도구 이름 + 인자를 정렬해 캐시 키를 만들고, 실행 전 `getCachedResult()`로 먼저 확인한다.
캐시가 없으면 실제로 DART/네이버를 호출한 뒤 `cacheResult()`로 저장한다. 단, 예상치 못한
오류(`RuntimeException`) 응답은 일시적일 수 있으므로 캐싱하지 않고, 정상 실행된 결과("찾지
못했습니다" 같은 정상적인 빈 결과 포함)만 캐싱한다.

---

## 9. PendingOrderDto

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

## 10. application.yml — Redis 설정

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
