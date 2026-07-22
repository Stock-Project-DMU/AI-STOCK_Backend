# NAMING.md — AI STOCK 네이밍 카탈로그

이 문서는 여러 명이 서로 다른 브랜치를 로컬에서 동시에 작업할 때
이름이 어긋나지 않도록, 사용할 클래스명·메서드명·필드명·API 경로를
미리 확정해두는 참조표다.

- CLAUDE.md의 네이밍 규칙(5번 항목)을 그대로 따른다.
- 여기 없는 이름이 새로 필요하면 임의로 만들지 말고 먼저 팀에 확인 후
  이 문서에 추가한다.
- Entity 필드명은 DB 컬럼(snake_case)을 camelCase로 그대로 옮긴 것이다.
  변형하지 않는다 (예: `frozen_balance` → `frozenBalance`).
- 브랜치 작업 순서(공통 로직 → 기능 개발)에 맞춰 섹션을 배치했다.

---

## 0. 전역 공통 Enum

여러 도메인에서 공유되므로 가장 먼저 확정한다. 위치는 각 Entity와 같은
패키지(`domain.xxx.entity`)에 두거나, 여러 도메인이 공유하는 경우
`global.util` 아래 별도 패키지 없이 해당 도메인 소속으로 둔다.

| Enum | 값 | 소속 |
|---|---|---|
| `Role` | `USER`, `ADMIN` | `domain.user.entity` |
| `SocialProvider` | `KAKAO`, `NAVER`, `GOOGLE` | `domain.user.entity` |
| `InvestmentLevel` | `BEGINNER`, `INTERMEDIATE`, `EXPERT` | `domain.user.entity` |
| `UserStatus` | `ACTIVE`, `SUSPENDED` | `domain.user.entity` (관리자에 의한 로그인 차단 — `isActive`/`deletedAt`의 본인 탈퇴와는 별개) |
| `OrderType` | `BUY`, `SELL` | `domain.order.entity` |
| `PriceType` | `LIMIT`, `MARKET` | `domain.order.entity` |
| `OrderStatus` | `PENDING`, `EXECUTED`, `CANCELLED` | `domain.order.entity` |
| `AccountStatus` | `ACTIVE`, `SUSPENDED` | `domain.account.entity` (관리자에 의한 계좌 거래 정지 — 로그인은 가능, 매수·매도만 차단) |
| `SessionStatus` | `ACTIVE`, `CLOSED` | `domain.ai.entity` |
| `MessageRole` | `USER`, `AI` | `domain.ai.entity` |
| `NotificationType` | `SYSTEM`, `ORDER`, `AI`, `SIMULATION` | `domain.notification.entity` |
| `InquiryStatus` | `PENDING`, `ANSWERED` | `domain.inquiry.entity` |

---

## 1. chore/db-entity — Entity & Repository

### 1-1. Entity 클래스 및 필드

| Entity | 필드 |
|---|---|
| `User` | `userId`, `loginId`, `password`, `name`, `birthdate`, `email`, `role`, `status`, `isActive`, `deletedAt`, `createdAt`, `updatedAt` |
| `SocialAccount` | `socialId`, `user`, `provider`, `providerId`, `createdAt` |
| `InvestmentProfile` | `profileId`, `user`, `investmentTendency`, `fundTendency`, `investmentLevel`, `surveyAnswers`, `createdAt`, `updatedAt` |
| `Account` | `accountId`, `user`, `accountName`, `accountNumber`, `openedAt`, `baseBalance`, `balance`, `frozenBalance`, `chargeCount`, `version`, `status`, `createdAt` |
| `Holding` | `holdingId`, `account`, `stockCode`, `stockName`, `quantity`, `avgPrice`, `updatedAt` |
| `Order` | `orderId`, `account`, `stockCode`, `stockName`, `orderType`, `priceType`, `orderPrice`, `execPrice`, `quantity`, `status`, `orderedAt`, `executedAt` |
| `Watchlist` | `watchlistId`, `user`, `stockCode`, `stockName`, `addedAt` |
| `AiPlanningSession` | `sessionId`, `user`, `title`, `status`, `createdAt`, `updatedAt` |
| `AiPlanningMessage` | `messageId`, `session`, `role`, `content`, `promptTokens`, `createdAt` |
| `Simulation` | `simulationId`, `user`, `stockCode`, `stockName`, `targetAmount`, `targetMonths`, `scenarioData`, `bestReachDate`, `baseReachDate`, `worstReachDate`, `dartData`, `newsData`, `createdAt` |
| `RecentViewed` | `viewId`, `user`, `stockCode`, `stockName`, `viewedAt` |
| `Notification` | `notiId`, `user`, `type`, `title`, `content`, `isRead`, `createdAt` |
| `Inquiry` | `inquiryId`, `user`, `title`, `content`, `status`, `answer`, `answeredBy`, `answeredAt`, `createdAt`, `updatedAt` |

- 연관관계 필드(`user`, `account`, `session`)는 `@ManyToOne` 객체 참조로 두고,
  DB 컬럼명(`user_id` 등)은 `@JoinColumn(name = "user_id")`로 매핑한다.
- `Inquiry.answeredBy`도 동일하게 `User` 타입 `@ManyToOne` 참조이며
  `@JoinColumn(name = "answered_by")`, `optional = true`(nullable). 문의 작성자인
  `user`와 혼동하지 않도록 필드명을 명확히 구분한다.
- 상태 변경 메서드는 Entity 안에 의미 있는 이름으로 둔다 (Setter 금지).
  예: `Account.applyBuyOrder(long amount)`, `Order.execute(long execPrice)`,
  `Order.cancel()`, `Notification.markAsRead()`, `User.deactivate()`(탈퇴 익명화),
  `Account.increaseVersion()`은 JPA `@Version`이 자동 처리하므로 별도 메서드 불필요.
- **관리자 정지/해제 메서드 (v8 추가)**: `User.suspend()`, `User.activate()`
  (status ACTIVE↔SUSPENDED 전환, `deactivate()`와는 별개), `Account.suspend()`,
  `Account.activate()` (status ACTIVE↔SUSPENDED 전환, 거래만 차단).
- **문의 답변 메서드 (v8 추가)**: `Inquiry.answer(String answer, User admin)` —
  `answer`, `answeredBy`, `answeredAt`을 한 번에 설정하고 `status`를 `ANSWERED`로 전환.
- **현재가 주문 체결 메서드 (feature/order-market 추가)**: `Account.applySellOrder(long amount)`
  (`applyBuyOrder`의 대칭 — 매도 대금을 잔고에 더함), `Holding.increase(int quantity, long execPrice)`
  (매수 체결 시 수량 증가 + 평단가 가중평균 재계산), `Holding.decrease(int quantity)`
  (매도 체결 시 수량 감소, 0이 되면 호출 측에서 `HoldingRepository.delete()`로 행 삭제).
- **지정가 주문 동결/정산 메서드 (feature/order-limit 추가)**: `Account.freezeForOrder(long amount)`
  (지정가 매수 주문금액을 `balance`→`frozenBalance`로 동결), `Account.unfreezeForOrder(long amount)`
  (취소 시 `frozenBalance`→`balance` 복원), `Account.settleFrozenOrder(long frozenAmount, long actualAmount)`
  (체결 시 동결 해제 + 지정가와 실제 체결가 차액을 `balance`로 환급).
- **가상캐시 충전 메서드 (feature/mypage-account 추가)**: `Account.chargeBalance(long chargeAmount)` —
  유저 1명이 계좌를 최대 3개(성향별로 나눠 투자)까지 만들 수 있고, 계좌마다 초기 1000만원
  외에 최대 3회까지 고정 1000만원씩 추가 충전이 가능하다(금액 고정, 시점은 유저 자유 — 가입
  직후 3번 연속 써도 무방). `balance`에 `chargeAmount`를 더하고(덮어쓰기 아님) `chargeCount`를
  1 증가시키며, `baseBalance`도 같은 금액만큼 함께 올린다 — 그렇지 않으면 충전으로 늘어난
  현금이 수익률 계산식 `(총자산-baseBalance)/baseBalance`에 그대로 섞여 들어가 실제 투자
  성과보다 수익률이 부풀어 보이는 문제가 생긴다(예: 원금 1000만으로 80% 수익 후 1000만
  충전 시, baseBalance를 안 올리면 표시 수익률이 180%로 왜곡됨). 최대 충전 횟수(3회) 검증은
  Entity가 아니라 `AccountService.chargeBalance()`에서 한다.

### 1-2. Repository 인터페이스 및 메서드

| Repository | 메서드 |
|---|---|
| `UserRepository` | `findByLoginId(String loginId)`, `findByEmail(String email)`, `existsByLoginId(String loginId)`, `existsByEmail(String email)`, `findByUserIdAndIsActiveTrue(Long userId)`, `countByIsActiveTrue()`(관리자 대시보드 — 총 사용자 수) |
| `SocialAccountRepository` | `findByProviderAndProviderId(SocialProvider provider, String providerId)`, `deleteByUserId(Long userId)` |
| `InvestmentProfileRepository` | `findByUserId(Long userId)`, `deleteByUserId(Long userId)` |
| `AccountRepository` | `findAllByUserId(Long userId)`(내 계좌 목록, 최대 3건), `findAllByUserIdForUpdate(Long userId)`(mypage-account 추가 — `@Lock(PESSIMISTIC_WRITE)`, `AccountService.createAccount()`가 계좌 개수 확인과 저장 사이의 동시 개설 경합을 막는 데 사용. 처음에는 `UserRepository.findByIdForUpdate`로 User 행 전체를 잠갔는데, User는 계좌와 무관한 다른 기능도 앞으로 잠글 수 있는 공용 자원이라 Account 쪽만 잠그는 이 메서드로 좁혔다 — 매칭 행이 0개여도 idx_account_user 인덱스로 갭 락이 걸려 동시 삽입을 막는다), `findByAccountIdAndUserId(Long accountId, Long userId)`(mypage-account 추가 — 계좌 소유권 검증 겸 조회. order-market/order-limit의 `findByUserId(Long userId)`를 대체 — 유저가 계좌를 여러 개 가질 수 있어 단일 계좌를 가정한 조회는 더 이상 쓰지 않는다), `findByAccountIdAndUserIdForUpdate(Long accountId, Long userId)`(mypage-account 추가 — `@Lock(PESSIMISTIC_WRITE)`, `AccountService.chargeBalance()`가 chargeCount 확인과 반영 사이의 동시 충전 경합을 막는 데 사용. `findByAccountIdAndUserId`와 WHERE 절이 동일해 `FIND_BY_ACCOUNT_ID_AND_USER_ID` 상수로 공유), `findByAccountNumber(String accountNumber)`, `deleteByUserId(Long userId)` |
| `HoldingRepository` | `findAllByAccountId(Long accountId)`, `findByAccountIdAndStockCode(Long accountId, String stockCode)` |
| `OrderRepository` | `findAllByStockCodeAndStatus(String stockCode, OrderStatus status)`, `findAllByAccountIdOrderByOrderedAtDesc(Long accountId)`, `findByOrderIdAndAccountId(Long orderId, Long accountId)`, `findAllByStatusWithAccountAndUser(OrderStatus status)`, `countByStatus(OrderStatus status)`(관리자 대시보드 — 총 거래건수), `findTop20ByStatusOrderByExecutedAtDesc(OrderStatus status)`(관리자 대시보드 — 최근 거래 20건), `findAllOrdersWithUser(Pageable pageable)`(관리자 전체 거래 목록, `@Query` JOIN FETCH account.user), `findOrderWithUserById(Long orderId)`(관리자 거래 상세, `@Query` JOIN FETCH), `sumExecutedAmount()`(관리자 대시보드 — 총 거래대금, `@Query SUM(execPrice*quantity)`), `findByIdForUpdate(Long orderId)`(feature/order-limit 추가 — `@Lock(PESSIMISTIC_WRITE)`, `OrderExecutionService.execute()`용), `findByOrderIdAndUserIdForUpdate(Long orderId, Long userId)`(mypage-account 추가 — `@Lock(PESSIMISTIC_WRITE)`, `OrderService.cancelOrder()`용. 계좌가 여러 개가 되면서 한때 `findByIdForUpdate(orderId)`로 먼저 잠근 뒤 소유자를 나중에 검증하는 방식을 썼는데, 그러면 남의 orderId로도 락이 먼저 걸려버려(락 경합 + 존재 여부를 응답 시간으로 구분당하는 사이드채널) 과거 `findByOrderIdAndAccountIdForUpdate(Long orderId, Long accountId)`처럼 소유권을 WHERE 절(이번엔 accountId 대신 userId로 조인)에 넣어 조회와 동시에 걸러내는 방식으로 되돌렸다), `sumPendingSellQuantity(Long accountId, String stockCode)`(feature/order-limit 추가 — 같은 계좌·종목으로 이미 등록된 PENDING 지정가 매도 주문 수량 합계. `createLimitOrder()`가 매도 등록 시 `보유수량 - 이미 대기 중인 매도 수량`으로 검증해, 같은 종목을 초과해서 중복 매도 등록하는 것을 등록 시점에 막는다. 이 조회는 일반 SELECT라 MySQL 기본 격리수준(REPEATABLE READ)에서는 트랜잭션 시작 시점 스냅샷을 볼 수 있어, `createLimitOrder()` 자체를 `@Transactional(isolation = READ_COMMITTED)`로 지정해 항상 최신 커밋 데이터를 보게 한다) |
| `WatchlistRepository` | `findAllByUserId(Long userId)`, `existsByUserIdAndStockCode(Long userId, String stockCode)`, `deleteByUserIdAndStockCode(Long userId, String stockCode)`, `deleteByUserId(Long userId)` |
| `AiPlanningSessionRepository` | `findAllByUserIdOrderByUpdatedAtDesc(Long userId)`, `findByUserIdAndSessionId(Long userId, Long sessionId)`, `deleteByUserId(Long userId)` |
| `AiPlanningMessageRepository` | `findAllBySessionIdOrderByCreatedAtAsc(Long sessionId)` |
| `SimulationRepository` | `findAllByUserIdOrderByCreatedAtDesc(Long userId)`, `findByUserIdAndSimulationId(Long userId, Long simulationId)`, `deleteByUserId(Long userId)` |
| `RecentViewedRepository` | `findAllByUserIdOrderByViewedAtDesc(Long userId)`, `findByUserIdAndStockCode(Long userId, String stockCode)`, `touchViewedAt(Long userId, String stockCode)`(mypage-account 추가 — `@Modifying`, 이미 본 종목을 다시 볼 때 새 행 대신 viewedAt만 UPDATE. delete 후 재삽입 방식은 `RecentViewed`가 `@GeneratedValue(IDENTITY)`라 save()가 즉시 INSERT를 실행해버려 아직 flush 안 된 DELETE와 충돌해 `uq_user_stock_view` 위반이 나는 버그가 있어 이 방식으로 교체했다), `deleteByUserId(Long userId)` |
| `NotificationRepository` | `findAllByUserIdOrderByCreatedAtDesc(Long userId)`, `countByUserIdAndIsReadFalse(Long userId)`, `findByNotiIdAndUserId(Long notiId, Long userId)` |
| `InquiryRepository` | `findAllByUserIdOrderByCreatedAtDesc(Long userId)`(사용자 본인 문의 목록), `findByInquiryIdAndUserId(Long inquiryId, Long userId)`(본인 문의 상세, 소유권 검증), `findAllByOrderByStatusDescCreatedAtDesc()`(관리자 전체 목록 — "PENDING"이 "ANSWERED"보다 알파벳순 뒤(P > A)라 status 내림차순 정렬해야 미답변 우선 노출), `deleteByUserId(Long userId)`(탈퇴 처리용) |

> `deleteByUserId`는 탈퇴 로직(문서 하단 8-3 참고)에서 공통으로 쓰인다. v8부터 `InquiryRepository.deleteByUserId`도 동일하게 탈퇴 처리 순서에 포함한다.

> **feature/order-limit 추가, v9에서 보강**: 같은 주문을 동시에 체결(`OrderExecutionService.execute`)/
> 취소(`OrderService.cancelOrder`)하려는 경합을 막기 위해 `findByIdForUpdate`/
> `findByOrderIdAndUserIdForUpdate`(mypage-account부터 이름 변경, 1-2 항목 참고)로 `Order` 행
> 자체에 비관적 락(`HoldingRepository`와 동일한 패턴)을 건다 — 두 트랜잭션이 겹치지 않고 항상
> 순서대로 처리되게 하는 주된 방법이다.
> 처음에는 `Order`에 `Account`와 달리 `@Version`이 없었는데, `OrderRepository`에는 저 두 메서드
> 외에도 잠금 없는 조회 메서드(`findByOrderIdAndAccountId`, `findAllOrdersWithUser`,
> `findOrderWithUserById`, 상속받은 `findById` 등 관리자 기능용)가 함께 존재해, 향후 그 경로로
> 조회한 `Order`에 `execute()`/`cancel()`을 호출하는 코드가 추가돼도 동시성 보호를 못 받는 구조적
> 위험이 있었다. v9에서 `accounts.version`과 동일한 패턴으로 `Order.version`(낙관적 락)을 추가해,
> 비관적 락 경로를 거치지 않은 수정이라도 JPA가 최소한의 동시 수정 충돌 감지를 하도록 보강했다.

---

## 2. feature/common-response — 공통 응답/예외

### 2-1. `ApiResponse<T>`
필드: `success`(boolean), `message`(String), `data`(T)
정적 팩토리: `ApiResponse.success(T data)`, `ApiResponse.success(String message, T data)`, `ApiResponse.error(String message)`

### 2-2. `ErrorCode` (enum) — 코드, HTTP 상태, 메시지 3요소

| Enum 값 | HTTP 상태 |
|---|---|
| `INVALID_INPUT` | 400 |
| `DUPLICATE_LOGIN_ID` | 409 |
| `DUPLICATE_EMAIL` | 409 |
| `INVALID_PASSWORD` | 401 |
| `LOGIN_LOCKED` | 423 |
| `USER_NOT_FOUND` | 404 |
| `INVALID_TOKEN` | 401 |
| `TOKEN_EXPIRED` | 401 |
| `REFRESH_TOKEN_NOT_FOUND` | 401 |
| `ACCESS_DENIED` | 403 |
| `EMAIL_CODE_MISMATCH` | 400 |
| `EMAIL_CODE_EXPIRED` | 400 |
| `ACCOUNT_NOT_FOUND` | 404 |
| `INSUFFICIENT_BALANCE` | 400 |
| `INSUFFICIENT_HOLDING` | 400 |
| `ORDER_NOT_FOUND` | 404 |
| `STOCK_NOT_FOUND` | 404 |
| `RESOURCE_NOT_FOUND` | 404 |
| `OPTIMISTIC_LOCK_CONFLICT` | 409 |
| `GEMINI_RATE_LIMIT_EXCEEDED` | 429 |
| `REDIS_SERIALIZATION_ERROR` | 500 |
| `EXTERNAL_API_ERROR` | 502 |
| `INTERNAL_SERVER_ERROR` | 500 |
| `USER_SUSPENDED` | 423 (v8 추가 — 관리자에 의해 정지된 계정 로그인 시도) |
| `ACCOUNT_SUSPENDED` | 400 (v8 추가 — 정지된 계좌로 주문 시도) |
| `INVALID_ADMIN_CODE` | 400 (v8 추가 — 관리자 회원가입 시 코드 불일치) |
| `INQUIRY_NOT_FOUND` | 404 (v8 추가) |
| `STOCK_PRICE_NOT_AVAILABLE` | 503 (order-market 추가 — 종목은 존재하지만 `stock:price:{stockCode}` Redis 캐시가 TTL 만료 등으로 비어 있어 현재가 주문을 체결할 수 없는 경우. `STOCK_NOT_FOUND`(종목 자체가 없음)와 혼동하지 않도록 분리) |
| `ORDER_ALREADY_PROCESSED` | 409 (order-limit 추가 — 이미 `EXECUTED`/`CANCELLED` 상태인 주문을 다시 취소(`DELETE /api/orders/{orderId}`)하려는 경우) |
| `ACCOUNT_LIMIT_EXCEEDED` | 400 (mypage-account 추가 — 유저가 이미 계좌 3개를 보유한 상태에서 추가 개설을 시도하는 경우) |
| `CHARGE_LIMIT_EXCEEDED` | 400 (mypage-account 추가 — 계좌의 `chargeCount`가 이미 3회에 도달한 상태에서 추가 충전을 시도하는 경우. 문의(inquiries) 기능으로 관리자에게 요청하도록 안내) |

### 2-3. 예외/핸들러
- `CustomException(ErrorCode errorCode)`, `CustomException(ErrorCode errorCode, Throwable cause)`
- `GlobalExceptionHandler` 메서드: `handleCustomException(CustomException e)`, `handleValidationException(MethodArgumentNotValidException e)`, `handleNoResourceFoundException(NoResourceFoundException e)`, `handleException(Exception e)`
- (feature/order-market 추가) `handleOptimisticLockingFailureException(ObjectOptimisticLockingFailureException e)` —
  Account.version 낙관적 락 충돌을 `ErrorCode.OPTIMISTIC_LOCK_CONFLICT`(409)로 변환해 응답한다.
  `DataIntegrityViolationException`(holdings.uq_account_stock 유니크 제약 위반)은 전역 핸들러로 두지
  않는다 — 앱 전체의 다른 제약 위반(예: 회원가입 중복 아이디)까지 같은 메시지로 뭉뚱그리게 되므로,
  `OrderService.executeBuy()`에서 신규 보유종목 INSERT 지점만 좁게 잡아 동일한 에러코드로 변환한다.

---

## 3. feature/jwt-common — 토큰

### `JwtProvider`
메서드: `createAccessToken(Long userId, Role role)`, `createRefreshToken(Long userId)`,
`validateToken(String token)`, `getUserId(String token)`, `getRole(String token)`,
`getRemainingMillis(String token)`, `resolveToken(HttpServletRequest request)`,
`extractBearerToken(String headerValue)` (HTTP/STOMP 공통 "Bearer " 접두어 제거 로직, StompAuthInterceptor에서도 사용)

### `JwtAuthenticationFilter`
메서드: `doFilterInternal(...)` (Spring 표준 오버라이드)

### `CustomUserDetailsService`
메서드: `loadUserByUsername(String userId)` → 내부적으로 `UserRepository.findByUserIdAndIsActiveTrue` 사용
(탈퇴 계정은 매 요청마다 인증 거부되도록 함), 반환 타입은 `CustomUserDetails`(userId, role 보유)

> v8부터 로그인 시점에는 `isActive`뿐 아니라 `status == SUSPENDED`도 함께 확인해야 하므로,
> 이 확인은 `CustomUserDetailsService`가 아니라 `AuthService.login()`에서 별도로
> `ErrorCode.USER_SUSPENDED`를 던지는 방식으로 처리한다 (인증 필터가 아닌 로그인 로직 시점에 명확히 안내하기 위함).

---

## 4. feature/security-config

### `SecurityConfig`
빈 메서드: `securityFilterChain(HttpSecurity http)`, `corsConfigurationSource()`,
`passwordEncoder()` (`BCryptPasswordEncoder`)

- v8 추가: `/api/admin/**` 경로는 `requestMatchers("/api/admin/**").hasRole("ADMIN")`로 제한.

### `JwtAuthenticationEntryPoint`
필드: `objectMapper`
메서드: `commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)`
— 미인증(토큰 없음/무효) 시 401 + `ApiResponse.error(ErrorCode.INVALID_TOKEN)` 응답

### `JwtAccessDeniedHandler`
필드: `objectMapper`
메서드: `handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException)`
— 인증은 됐으나 권한 부족 시 403 + `ApiResponse.error(ErrorCode.ACCESS_DENIED)` 응답
(v8부터 일반 사용자가 `/api/admin/**` 접근 시에도 이 핸들러가 403 처리)

---

## 5. feature/websocket-config

### `WebSocketConfig`
STOMP 엔드포인트: `/ws-stomp`
토픽: `/topic/stock/{stockCode}` (브로드캐스팅), `/queue`(유니캐스팅 prefix), `/app`(publish prefix)

### `StompAuthInterceptor`
메서드: `preSend(Message<?> message, MessageChannel channel)`

> v8 추가: CONNECT 커맨드 검증 통과 시 `RedisOnlineStatusService.addOnline(userId)` 호출,
> DISCONNECT 커맨드 수신 시 `RedisOnlineStatusService.removeOnline(userId)` 호출.
> 클라이언트 비정상 종료(DISCONNECT 프레임 없이 연결만 끊김) 대비 `SessionDisconnectEvent`를
> `@EventListener`로 별도 처리하는 보완 로직 필요 (구현 시 별도 메서드 `onSessionDisconnect(SessionDisconnectEvent event)`로 추가).

### `AsyncConfig`
빈: `tickTaskExecutor()` — 스레드풀 이름 prefix `tick-executor-`

### `RedisConfig`
빈: `redisTemplate(RedisConnectionFactory factory)` — Key/Value/Hash 전부 `StringRedisSerializer`.
`RedisConnectionFactory`는 커스텀 빈으로 정의하지 않고 Spring Boot Data Redis 오토설정에 위임한다.

### `JpaConfig`
`@EnableJpaAuditing`만 선언 (Entity의 `@CreatedDate`/`@LastModifiedDate` 활성화 용도, 별도 빈 없음)

### `AwsParameterStoreConfig`
AWS Parameter Store에서 민감한 설정값(JWT_SECRET, DB 자격증명, `ADMIN_SIGNUP_CODE` 등)을 읽어오는 설정 (구현 예정)

---

## 6. feature/ls-websocket

| 클래스 | 주요 메서드 |
|---|---|
| `LsWebSocketClient` | `connect()`, `subscribe(String stockCode)`, `unsubscribe(String stockCode)`, `disconnect()` |
| `LsWebSocketHandler` | `handleMessage(String rawMessage)`, `onTickReceived(LsTickData tickData)`, `onHogaReceived(LsHogaData hogaData)` |
| `LsReconnectService` | `scheduleReconnect()`, `reconnectWithBackoff()` |
| `LsTickData` (dto) | `stockCode`, `stockName`, `currentPrice`, `changeRate`, `volume`, `tradedAt` |
| `LsHogaData` (dto) | `stockCode`, `askPrices`(List), `askVolumes`(List), `bidPrices`(List), `bidVolumes`(List) |

---

## 7. feature/redis-service

redis-logic.md(수정본) 기준 확정된 이름 그대로 사용:

| 클래스 | 주요 메서드 |
|---|---|
| `RedisTokenService` | `saveRefreshToken`, `getRefreshToken`, `isRefreshTokenValid`, `deleteRefreshToken`, `blacklistAccessToken`, `isBlacklisted` |
| `RedisAuthCodeService` | `saveEmailCode`, `verifyAndDeleteEmailCode`, `incrementLoginFail`, `isLoginLocked`, `resetLoginFail` |
| `RedisStockCacheService` | `saveStockPrice`, `getStockPrice`, `saveHogaData`, `getHogaData` |
| `RedisPendingOrderService` | `initPendingOrders`, `addPendingOrder`, `getPendingOrders`, `removePendingOrder` |
| `RedisRateLimiterService` | `isAllowed`, `increment`, `getRemainingDaily` |
| `RedisOnlineStatusService` (v8 추가) | `clearOnlineStatus()`(서버 재시작 시 `@PostConstruct` 초기화, v9), `addOnline(Long userId)`, `removeOnline(Long userId)`, `countOnline()`, `isOnline(Long userId)` |

DTO: `StockPriceDto`(stockCode, stockName, currentPrice, changeAmount, changeRate, volume, updatedAt), `HogaDto`(stockCode, askPrices, askVolumes, bidPrices, bidVolumes, updatedAt), `PendingOrderDto`(redis-logic.md 확정본과 동일)

---

## 8. 기능 개발 브랜치

### 8-1. feature/auth-login

| 구분 | 이름 |
|---|---|
| Controller | `AuthController` |
| 엔드포인트 | `POST /api/auth/login`, `POST /api/auth/oauth/{provider}`, `POST /api/auth/refresh` |
| Service | `AuthService` — `login(LoginRequest request)`, `oauthLogin(String provider, OAuthLoginRequest request)`, `reissueToken(String refreshToken)` |
| Request DTO | `LoginRequest`(loginId, password), `OAuthLoginRequest`(authorizationCode) |
| Response DTO | `LoginResponse`(accessToken, refreshToken), `TokenResponse`(accessToken, refreshToken) |
| OAuth Client | `KakaoOAuthClient.getUserInfo(String code)`, `NaverOAuthClient.getUserInfo(String code)`, `GoogleOAuthClient.getUserInfo(String code)` |
| OAuth Dto | `KakaoUserInfo`(providerId, email, nickname, birthday, birthyear), `NaverUserInfo`(providerId, email, name, birthday, birthyear), `GoogleUserInfo`(providerId, email, name) |
| Util | `SecurityUtil.getCurrentUserId()` |

> v8 추가: `AuthService.login()`에서 `user.getStatus() == UserStatus.SUSPENDED`인 경우
> `CustomException(ErrorCode.USER_SUSPENDED)` throw (기존 `isActive`/탈퇴 확인과 별도 분기).

### 8-2. feature/auth-signup

| 구분 | 이름 |
|---|---|
| 엔드포인트 (AuthController 추가) | `POST /api/auth/signup`, `POST /api/auth/email/send-code`, `POST /api/auth/email/verify-code` |
| Service (AuthService 추가) | `signup(SignupRequest request)`, `sendEmailCode(String email)`, `verifyEmailCode(String email, String code)` |
| Request DTO | `SignupRequest`(loginId, password, name, email, birthdate, `role`, `adminCode`), `EmailCodeRequest`(email), `EmailCodeVerifyRequest`(email, code) |
| Response DTO | `SignupResponse`(userId, loginId, role) |
| Util | `DateUtil.parseSocialBirthdate(String birthday, String birthyear)` |

> v8 추가: `SignupRequest.role`(기본값 `USER`)이 `ADMIN`이면 `adminCode`가 필수이며,
> `AuthService.signup()`에서 서버 환경변수 `ADMIN_SIGNUP_CODE`와 대조 후 불일치 시
> `CustomException(ErrorCode.INVALID_ADMIN_CODE)` throw. 일치해야만 `Role.ADMIN`으로 가입.

### 8-3. feature/auth-logout

| 구분 | 이름 |
|---|---|
| 엔드포인트 (AuthController 추가) | `POST /api/auth/logout` |
| Service (AuthService 추가) | `logout(Long userId, String accessToken)` |
| 탈퇴 처리 (UserService, 별도 확정 필요 시 브랜치 지정) | `UserService.withdraw(Long userId)` — 내부에서 각 Repository의 `deleteByUserId` 순차 호출(`InquiryRepository.deleteByUserId` 포함, v8) 후 `RedisTokenService.deleteRefreshToken` → `User` 익명화 |

### 8-4. feature/stock-price

| 구분 | 이름 |
|---|---|
| Controller | `StockController` |
| 엔드포인트 | `GET /api/stocks/{stockCode}`, `GET /api/stocks/{stockCode}/hoga` |
| Service | `StockService` — `getCurrentPrice(String stockCode)`, `getHoga(String stockCode)` |
| Broadcast | `StockBroadcastService` — `onTickReceived(LsTickData tickData)`, `broadcastPrice(String stockCode, StockPriceDto dto)` |
| Response DTO | `StockPriceResponse`(stockCode, stockName, currentPrice, changeRate, volume), `HogaResponse`(stockCode, askPrices, askVolumes, bidPrices, bidVolumes) |

### 8-5. feature/order-market

| 구분 | 이름 |
|---|---|
| Controller | `OrderController` |
| 엔드포인트 | `POST /api/orders` |
| Service | `OrderService` — `createMarketOrder(Long userId, CreateOrderRequest request)` |
| Request DTO | `CreateOrderRequest`(accountId, stockCode, orderType, quantity, priceType, orderPrice) |
| Response DTO | `CreateOrderResponse`(orderId, stockCode, execPrice, quantity, status) |

> v8 추가: `OrderService.createMarketOrder()` 진입 시 `account.getStatus() == AccountStatus.SUSPENDED`면
> `CustomException(ErrorCode.ACCOUNT_SUSPENDED)` throw. 계좌 정지는 로그인/조회는 허용하되 거래
> 관련 행위 전부를 막는 정책이라, `createLimitOrder()`·`cancelOrder()`(주문 취소도 거래 행위로
> 간주)에도 동일하게 적용한다.
>
> **mypage-account 추가**: 유저 1명이 계좌를 최대 3개까지 가질 수 있게 되면서, 어느 계좌로
> 주문할지 클라이언트가 명시해야 한다. `CreateOrderRequest.accountId`를 추가했고,
> `createMarketOrder()`/`createLimitOrder()`의 계좌 조회를
> `accountRepository.findByUserId(userId)`(단일 계좌 가정, 더 이상 존재하지 않는 메서드) 대신
> `accountRepository.findByAccountIdAndUserId(request.accountId(), userId)`로 바꿔 "내 계좌가
> 맞는지" 소유권까지 함께 검증한다. `cancelOrder(Long userId, Long orderId)`는 시그니처를
> 바꾸지 않는다 — `OrderRepository.findByOrderIdAndUserIdForUpdate(orderId, userId)`로 조회와
> 동시에 소유권을 검증하며 비관적 락을 건다(1-2 `OrderRepository` 항목 참고). 처음에는
> `findByIdForUpdate(orderId)`로 주문을 먼저 잠근 뒤 `order.getAccount().getUser()`로 소유자를
> 나중에 확인하는 방식으로 짰었는데, 그러면 남의 orderId를 넣어도 소유권 확인 전에 비관적
> 락부터 걸려버려 `OrderExecutionService.execute()`와의 불필요한 락 경합 및 "존재하지만 내 것이
> 아님"과 "존재하지 않음"을 응답 시간 차이로 구분당하는 타이밍 사이드채널이 생기는 문제가
> 코드 리뷰에서 발견돼, 소유권(user_id)을 WHERE 절에 넣어 조회 자체를 소유자 본인 소유의
> 행에만 걸리게 하는 방식으로 정정했다(불일치 시 `ErrorCode.ORDER_NOT_FOUND`로 응답).

### 8-6. feature/order-limit

| 구분 | 이름 |
|---|---|
| 엔드포인트 (OrderController 추가) | `POST /api/orders` (priceType=LIMIT 공용), `DELETE /api/orders/{orderId}` |
| Service (OrderService 추가) | `createLimitOrder(Long userId, CreateOrderRequest request)`, `cancelOrder(Long userId, Long orderId)` |
| Execution Service | `OrderExecutionService` — `execute(PendingOrderDto pendingOrder, long currentPrice)`, `checkAndExecute(String stockCode, long currentPrice)` |
| Response DTO | `OrderHistoryResponse`(orderId, stockCode, stockName, orderType, priceType, orderPrice, execPrice, quantity, status, orderedAt, executedAt) |
| Holding 공용 서비스 | `HoldingSettlementService`(domain/order/service) — `increaseOrCreate(Account account, String stockCode, String stockName, int quantity, long execPrice)`, `decrease(Holding holding, int quantity)`. `OrderService.executeBuy()`/`executeSell()`(시장가)와 `OrderExecutionService.executeBuy()`/`executeSell()`(지정가)가 각자 갖고 있던 동일한 보유종목 갱신 로직을 하나로 합친 것 |

> feature/order-limit 정리: `createMarketOrder()`/`createLimitOrder()`의 매수·매도 체결이 각자
> 중복 구현하고 있던 "보유종목 조회 → 있으면 증가, 없으면 신규 생성(uq_account_stock 충돌 시
> OPTIMISTIC_LOCK_CONFLICT)"과 "보유수량 차감, 0이 되면 행 삭제" 로직을 `HoldingSettlementService`로
> 추출했다. 잔고(balance/frozenBalance) 반영과 보유수량 충분 여부 검증은 호출부(시장가는 즉시
> 예외, 지정가 체결은 실패 시 주문 취소)마다 의미가 달라 그대로 각 서비스에 남겨뒀다.

> order-limit 반영: `OrderController.createOrder()`는 이제 `request.priceType()`으로
> `createMarketOrder()`/`createLimitOrder()`를 실제로 분기한다. order-market 단계에 있던
> `ErrorCode.INVALID_PRICE_TYPE`(LIMIT 요청을 막던 임시 처리)는 더 이상 쓰이지 않아 제거했다.
> `cancelOrder()`는 소유권 검증 + 비관적 락(mypage-account부터 `findByOrderIdAndUserIdForUpdate`,
> 1-2 항목 참고) 후 `status != PENDING`이면 `ErrorCode.ORDER_ALREADY_PROCESSED`(409) throw.
>
> `OrderExecutionService`는 `self`(`@Autowired @Lazy` 필드, 자기 자신의 프록시)를 통해
> `checkAndExecute()`에서 `execute()`를 호출한다 — 같은 클래스 안에서 `this.execute(...)`처럼
> 직접 호출하면(self-invocation) Spring AOP 프록시를 우회해 `execute()`의 `@Transactional`이
> 전혀 적용되지 않기 때문이다. `createLimitOrder()`/`cancelOrder()`의 Redis 반영(`addPendingOrder`/
> `removePendingOrder`)도 트랜잭션이 실제로 커밋된 뒤에만 실행되도록
> `TransactionSynchronizationManager.registerSynchronization(...afterCommit)`으로 미룬다 —
> 트랜잭션이 롤백되면 DB와 Redis 상태가 어긋나는 것을 막기 위함이다.

### 8-7. feature/mypage-account

| 구분 | 이름 |
|---|---|
| Controller | `AccountController`, `WatchlistController`, `RecentViewedController` |
| 엔드포인트 | `GET /api/accounts`(내 계좌 목록, 최대 3개), `POST /api/accounts`(계좌 개설), `POST /api/accounts/{accountId}/charge`(가상캐시 충전), `GET /api/watchlist`, `POST /api/watchlist`, `DELETE /api/watchlist/{stockCode}`, `GET /api/recent-viewed`, `POST /api/recent-viewed` |
| Service | `AccountService` — `getMyAccounts(Long userId)`, `createAccount(Long userId, CreateAccountRequest request)`, `chargeBalance(Long userId, Long accountId)` / `WatchlistService` — `getMyWatchlist(Long userId)`, `addWatchlist(Long userId, String stockCode)`, `removeWatchlist(Long userId, String stockCode)` / `RecentViewedService` — `getMyRecentViewed(Long userId)`, `recordView(Long userId, String stockCode)` |
| Request DTO | `CreateAccountRequest`(accountName), `WatchlistRequest`(stockCode), `RecentViewedRequest`(stockCode) |
| Response DTO | `AccountInfoResponse`(accountId, accountName, accountNumber, balance, frozenBalance, baseBalance, chargeCount, `status`), `WatchlistResponse`(stockCode, stockName, addedAt), `RecentViewedResponse`(stockCode, stockName, viewedAt) |

> 유저 1명당 계좌 최대 3개(성향별로 나눠 투자 가능 — 예: "계좌 A"는 안정적으로, "계좌 B"는
> 공격적으로), 계좌당 가상캐시 충전 최대 3회(1회당 고정 1000만원, 시점은 유저 자유 — 가입
> 직후 연속으로 3번 다 써도 무방)라는 제약이 있다. `createAccount()`는 `accountRepository.
> findAllByUserIdForUpdate(userId).size() >= 3`이면 `ErrorCode.ACCOUNT_LIMIT_EXCEEDED`, `chargeBalance()`는
> `account.getChargeCount() >= 3`이면 `ErrorCode.CHARGE_LIMIT_EXCEEDED`를 던진다(3회 초과분은
> 기존 문의(inquiries) 기능으로 관리자에게 요청하도록 안내 — 관리자 승인 처리 자체는 이번
> 범위 밖). 회원가입 시 계좌 자동 생성은 아직 구현되지 않은 feature/auth-signup 몫이라, 이
> 브랜치에서는 최초 계좌든 추가 계좌든 전부 `createAccount()` 하나로 통일해 나중에 auth-signup이
> 그대로 재사용할 수 있게 한다. 계좌 개설 시 `balance`/`baseBalance`는 항상 고정 1000만원으로
> 시작한다(스키마 DEFAULT와 동일). 계좌번호(`accountNumber`)는 실제 증권사 계좌 체계를 흉내낼
> 필요가 없는 모의투자 서비스라 UUID 일부로 생성한다. 계좌 개수/`chargeCount` 확인은
> 확인(check)과 반영(act) 사이에 경합이 생길 수 있어(`accounts.user_id`에 유니크 제약이 없어
> DB가 대신 막아주지 못함), `createAccount()`는 `AccountRepository.findAllByUserIdForUpdate(userId)`로,
> `chargeBalance()`는 `AccountRepository.findByAccountIdAndUserIdForUpdate(accountId, userId)`로
> 각각 비관적 락을 걸어 동시 요청을 순서대로 처리한다(코드 리뷰에서 발견돼 반영). 둘 다 Account
> 쪽만 잠근다 — 처음에는 `createAccount()`가 `UserRepository.findByIdForUpdate`로 User 행
> 전체를 잠갔었는데, User는 로그인·관리자 정지처럼 계좌와 무관한 다른 기능도 앞으로 잠글 수
> 있는 공용 자원이라 불필요하게 넓은 락이라는 지적을 받아 Account 쪽으로 좁혔다(이제
> `UserRepository`에는 이 메서드가 없다).
>
> Watchlist/RecentViewed는 계좌가 아니라 유저 단위(schema.sql `user_id` FK)라 계좌 다중화의
> 영향을 받지 않는다. `stockName`은 클라이언트가 보내지 않고, `OrderService`와 동일한 이유로
> (feature/stock-price 미구현) `RedisStockCacheService.getStockPrice(stockCode)`를 직접 조회해
> 채운다 — 캐시가 비어 있으면(TTL 만료) `ErrorCode.STOCK_PRICE_NOT_AVAILABLE`을 던진다.
> `WatchlistService.addWatchlist()`/`removeWatchlist()`는 멱등하게 처리한다(이미 추가된 종목을
> 다시 추가하거나, 없는 종목을 삭제해도 에러 없이 그대로 둔다). `RecentViewedService.
> recordView()`는 같은 종목을 다시 보면(uq_user_stock_view) 새 행을 추가하지 않고 viewedAt만
> 갱신해야 하는데, `RecentViewed`에 viewedAt 외에 바뀌는 필드가 없어 기존 행을 그대로 다시
> save()해도 Hibernate가 변경분 없음으로 판단해 UPDATE 자체를 스킵한다. 처음에는 기존 행을
> 지우고 새로 insert하는 방식으로 짰었는데, `RecentViewed`가 `@GeneratedValue(IDENTITY)`라
> save()가 즉시 INSERT를 실행해버려 아직 flush 안 된 DELETE와 순서가 꼬여
> `uq_user_stock_view` 위반이 나는 버그가 코드 리뷰에서 발견돼, `RecentViewedRepository.
> touchViewedAt()`(1-2 항목 참고)으로 UPDATE 쿼리를 직접 날리는 방식으로 정정했다. 갱신된 행이
> 없으면(처음 보는 종목) 그때만 Redis에서 종목명을 얻어 새 행을 만든다.

### 8-8. feature/mypage-profit

| 구분 | 이름 |
|---|---|
| 엔드포인트 (AccountController, OrderController, UserController 추가) | `GET /api/accounts/me/profit`, `GET /api/orders`, `GET /api/orders/holdings`, `GET /api/users/me`, `PATCH /api/users/me`, `POST /api/users/me/survey` |
| Service | `AccountService.getProfit(Long userId)` / `OrderService.getMyOrderHistory(Long userId)`, `OrderService.getMyHoldings(Long userId)` / `UserService` — `getMyInfo(Long userId)`, `updateMyInfo(Long userId, UpdateUserRequest request)`, `saveSurvey(Long userId, SurveyRequest request)` |
| Response DTO | `ProfitResponse`(totalAsset, profitAmount, profitRate), `HoldingResponse`(stockCode, stockName, quantity, avgPrice, currentPrice, evaluationProfit), `UserInfoResponse`(userId, loginId, name, email, role, `status`), `InvestmentProfileResponse`(investmentTendency, fundTendency, investmentLevel) |
| Request DTO | `UpdateUserRequest`(name, email), `SurveyRequest`(answers, investmentTendency, fundTendency) |

### 8-9. feature/ai-planning

| 구분 | 이름 |
|---|---|
| Controller | `AiPlanningController` |
| 엔드포인트 | `POST /api/ai/planning/sessions`, `GET /api/ai/planning/sessions`, `GET /api/ai/planning/sessions/{sessionId}/messages`, `POST /api/ai/planning/sessions/{sessionId}/messages` |
| Service | `AiPlanningService` — `createSession(Long userId)`, `getMySessions(Long userId)`, `getMessages(Long userId, Long sessionId)`, `sendMessage(Long userId, Long sessionId, AiChatRequest request)` |
| Request DTO | `AiChatRequest`(content) |
| Response DTO | `AiChatResponse`(messageId, role, content, createdAt) |
| Infra Client | `GeminiApiClient.generate(GeminiRequest request)`, `DartApiClient.getFinancials(DartFinancialRequest request)`, `TavilyApiClient.search(TavilySearchRequest request)` |
| Infra Dto | `GeminiRequest`(prompt, history), `GeminiResponse`(content, tokenCount), `DartFinancialRequest`(corpCode, year), `DartFinancialResponse`(...), `TavilySearchRequest`(query), `TavilySearchResponse`(results) |

### 8-10. feature/ai-news

| 구분 | 이름 |
|---|---|
| Controller | `AiNewsController` |
| 엔드포인트 | `GET /api/ai/news` |
| Service | `AiNewsService` — `searchNews(String keyword)` |
| Request DTO | `NewsSearchRequest`(keyword) |
| Response DTO | `NewsSearchResponse`(title, url, summary, publishedAt) |

### 8-11. feature/simulation

| 구분 | 이름 |
|---|---|
| Controller | `SimulationController` |
| 엔드포인트 | `POST /api/simulations`, `GET /api/simulations`, `GET /api/simulations/{simulationId}` |
| Service | `SimulationService` — `runSimulation(Long userId, SimulationRequest request)`, `getMySimulations(Long userId)`, `getSimulation(Long userId, Long simulationId)` |
| Request DTO | `SimulationRequest`(stockCode, targetAmount, targetMonths) |
| Response DTO | `SimulationResponse`(simulationId, stockCode, bestScenario, baseScenario, worstScenario, bestReachDate, baseReachDate, worstReachDate) |

### 8-12. feature/notification

| 구분 | 이름 |
|---|---|
| Controller | `NotificationController` |
| 엔드포인트 | `GET /api/notifications`, `PATCH /api/notifications/{notiId}/read`, `GET /api/notifications/unread-count` |
| Service | `NotificationService` — `getMyNotifications(Long userId)`, `markAsRead(Long userId, Long notiId)`, `getUnreadCount(Long userId)`, `notify(Long userId, NotificationType type, String title, String content)`(내부 발송용) |
| Response DTO | `NotificationResponse`(notiId, type, title, content, isRead, createdAt), `NotificationCountResponse`(unreadCount) |

### 8-13. feature/inquiry (v8 신규 — 사용자 측 문의)

| 구분 | 이름 |
|---|---|
| Controller | `InquiryController` |
| 엔드포인트 | `POST /api/inquiries`, `GET /api/inquiries`, `GET /api/inquiries/{inquiryId}` |
| Service | `InquiryService` — `createInquiry(Long userId, CreateInquiryRequest request)`, `getMyInquiries(Long userId)`, `getMyInquiryDetail(Long userId, Long inquiryId)` |
| Request DTO | `CreateInquiryRequest`(title, content) |
| Response DTO | `InquiryResponse`(inquiryId, title, content, status, answer, answeredAt, createdAt) |

> `getMyInquiryDetail`은 `InquiryRepository.findByInquiryIdAndUserId`로 소유권을 함께 검증하여,
> 다른 사용자의 문의를 조회할 수 없도록 한다. 상세 조회 시 미답변이면 `answer`/`answeredAt`은 `null`.

### 8-14. feature/admin-dashboard (v8 신규)

| 구분 | 이름 |
|---|---|
| Controller | `AdminDashboardController` |
| 엔드포인트 | `GET /api/admin/dashboard` |
| Service | `AdminDashboardService` — `getDashboard()` |
| Response DTO | `AdminDashboardResponse`(totalUserCount, onlineUserCount, totalTradeCount, totalTradeAmount, recentTrades: `List<RecentTradeResponse>`) |
| 내부 DTO | `RecentTradeResponse`(orderId, userName, stockCode, stockName, orderType, execPrice, quantity, executedAt) |

> `totalUserCount` = `UserRepository.countByIsActiveTrue()`,
> `onlineUserCount` = `RedisOnlineStatusService.countOnline()`,
> `totalTradeCount`/`totalTradeAmount` = `OrderRepository.countByStatus(EXECUTED)`/`sumExecutedAmount()`,
> `recentTrades` = `OrderRepository.findTop20ByStatusOrderByExecutedAtDesc(EXECUTED)`.

### 8-15. feature/admin-trade (v8 신규)

| 구분 | 이름 |
|---|---|
| Controller | `AdminTradeController` |
| 엔드포인트 | `GET /api/admin/trades`, `GET /api/admin/trades/{orderId}` |
| Service | `AdminTradeService` — `getTrades(Pageable pageable)`, `getTradeDetail(Long orderId)` |
| Response DTO | `AdminTradeResponse`(orderId, userName, loginId, stockCode, stockName, orderType, priceType, orderPrice, execPrice, quantity, status, orderedAt, executedAt) |

### 8-16. feature/admin-account (v8 신규)

| 구분 | 이름 |
|---|---|
| Controller | `AdminAccountController` |
| 엔드포인트 | `GET /api/admin/accounts/{accountId}`, `PATCH /api/admin/accounts/{accountId}/status` |
| Service | `AdminAccountService` — `getAccountDetail(Long accountId)`, `updateAccountStatus(Long accountId, AdminAccountStatusRequest request)` |
| Request DTO | `AdminAccountStatusRequest`(status) |
| Response DTO | `AdminAccountDetailResponse`(accountId, userName, accountNumber, balance, frozenBalance, baseBalance, status) |

### 8-17. feature/admin-user (v8 신규)

| 구분 | 이름 |
|---|---|
| Controller | `AdminUserController` |
| 엔드포인트 | `GET /api/admin/users`, `GET /api/admin/users/{userId}`, `PATCH /api/admin/users/{userId}/status` |
| Service | `AdminUserService` — `getUsers(Pageable pageable)`, `getUserDetail(Long userId)`, `updateUserStatus(Long userId, AdminUserStatusRequest request)` |
| Request DTO | `AdminUserStatusRequest`(status) |
| Response DTO | `AdminUserListResponse`(userId, loginId, name, email, role, status, createdAt), `AdminUserDetailResponse`(기본정보 필드 + `account`: 기존 `AccountInfoResponse` 재사용 + `holdings`: 기존 `List<HoldingResponse>` 재사용 + `orders`: 기존 `List<OrderHistoryResponse>` 재사용) |

> `AdminUserDetailResponse`는 기존 마이페이지용 DTO(`AccountInfoResponse`, `HoldingResponse`, `OrderHistoryResponse`)를 그대로 내부 필드로 재사용한다 — 동일한 형태의 응답 DTO를 중복 정의하지 않는다.

### 8-18. feature/admin-inquiry (v8 신규 — 관리자 측 문의 확인/답변)

| 구분 | 이름 |
|---|---|
| Controller | `AdminInquiryController` |
| 엔드포인트 | `GET /api/admin/inquiries`, `GET /api/admin/inquiries/{inquiryId}`, `PATCH /api/admin/inquiries/{inquiryId}/answer` |
| Service | `AdminInquiryService` — `getInquiries(Pageable pageable)`, `getInquiryDetail(Long inquiryId)`, `answerInquiry(Long adminUserId, Long inquiryId, AdminInquiryAnswerRequest request)` |
| Request DTO | `AdminInquiryAnswerRequest`(answer) |
| Response DTO | `AdminInquiryResponse`(inquiryId, userName, loginId, title, content, status, answer, answeredAt, createdAt) |

> `answerInquiry`는 내부에서 `Inquiry.answer(String answer, User admin)` 엔티티 메서드를 호출한다
> (1-1 참고). `feature/inquiry`(사용자 측)와 `feature/admin-inquiry`(관리자 측)는 같은
> `Inquiry` Entity·`InquiryRepository`를 공유하되 Controller/Service/DTO는 분리한다.

---

## 9. 공통 변수명 컨벤션 (모든 도메인 공통 적용)

| 상황 | 변수명 |
|---|---|
| 현재 로그인한 사용자 ID | `userId` (SecurityUtil.getCurrentUserId() 반환값) |
| 경로 변수 | `{stockCode}`, `{orderId}`, `{sessionId}`, `{simulationId}`, `{notiId}`, `{inquiryId}`, `{accountId}` — Controller 파라미터명도 동일하게 맞춤 |
| 페이지네이션 사용 시 | `page`, `size`, `sort` (쿼리 파라미터), 반환은 `Page<T>` 또는 `List<T>` 중 도메인별 통일 필요 시 별도 협의. 관리자 목록 API(`admin-trade`, `admin-user`, `admin-inquiry`)는 데이터量이 많아질 수 있어 `Page<T>`로 통일한다. |
| 목록 반환 변수 | 복수형 (`orders`, `holdings`, `notifications`) |
