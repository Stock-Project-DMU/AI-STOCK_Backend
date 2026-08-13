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
- **평가금액 조회 메서드 (feature/mypage-profit 코드리뷰 반영)**: `Holding.resolveValuationPrice(Long
  currentPrice)` — 마이페이지 수익률/보유종목 조회 시 평가금액 계산용 현재가를 반환한다.
  `currentPrice`가 null(시세 캐시 미스)이면 `avgPrice`로 대체한다. `AccountService.getProfit()`/
  `OrderService.getMyHoldings()` 양쪽에서 같은 폴백 로직을 복붙하던 것을 이 메서드 하나로
  모았다. stock 도메인의 `StockPriceDto`를 직접 받지 않고 `Long`만 받는다 — order 도메인
  엔티티가 다른 도메인의 DTO 타입에 의존하지 않도록, "캐시에서 현재가를 꺼내는" 책임은
  호출부(이미 `StockPriceDto`를 쓰고 있는 `AccountService`/`OrderService`)에 남겨둔다.
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
| `EMAIL_NOT_VERIFIED` | 400 (v8 추가 — 이메일 인증을 거치지 않고 `signup()`을 호출한 경우) |
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
| `RedisAuthCodeService` | `saveEmailCode`, `verifyAndDeleteEmailCode`, `markEmailVerified`(v8 추가), `consumeEmailVerified`(v8 추가), `incrementLoginFail`, `isLoginLocked`, `resetLoginFail` |
| `RedisStockCacheService` | `saveStockPrice`, `getStockPrice`, `saveHogaData`, `getHogaData`, `getStockPrices(Collection<String> stockCodes)`(feature/mypage-profit 코드리뷰 반영 — Redis MGET으로 여러 종목 시세를 한 번에 배치 조회. 종목마다 `getStockPrice()`를 순차 호출하면 보유종목이 N개일 때 N번 왕복이 생기는 문제를 막기 위함. 캐시 미스 종목은 반환 `Map`에서 키 자체가 빠짐) |
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
| 엔드포인트 | `POST /api/auth/login`, `POST /api/auth/oauth/login`, `POST /api/auth/refresh` |
| Service | `AuthService` — `login(LoginRequest request)`, `socialLogin(OAuthLoginRequest request)`, `processSocialLogin(SocialProvider provider, SocialUserDto userInfo)`(소셜 로그인 실제 처리 — `socialLogin()`이 self 프록시로만 호출하는 `REQUIRES_NEW` 내부 메서드, 아래 참고), `refresh(String authHeader)` |
| Request DTO | `LoginRequest`(loginId, password), `OAuthLoginRequest`(provider, code) |
| Response DTO | `LoginResponse`(accessToken, refreshToken, userId, name, email), `TokenResponse`(accessToken, refreshToken) |
| OAuth Client | `OAuthClient`(인터페이스) — `getProvider()`, `getUserInfo(String code): SocialUserDto`. `KakaoOAuthClient`/`NaverOAuthClient`/`GoogleOAuthClient`가 구현 |
| OAuth Dto | `SocialUserDto`(providerId, email, name) — Provider 공통 규격. 각 Provider 응답 파싱 전용 DTO는 `toSocialUserDto()`로 변환: `KakaoUserDto`(id, kakaoAccount{email, profile{nickname}}), `NaverUserDto`(resultCode, message, response{id, email, name}), `GoogleUserDto`(id, email, name) |
| Util | `SecurityUtil.getCurrentUserId()` |

> v8 추가: `AuthService.login()`에서 `user.getStatus() == UserStatus.SUSPENDED`인 경우
> `CustomException(ErrorCode.USER_SUSPENDED)` throw (기존 `isActive`/탈퇴 확인과 별도 분기).

> feature/auth-logout 추가: `login()`이 같은 `loginId`로 반복되는 로그인 시도(브루트포스)를
> 막기 위해 `RedisAuthCodeService`의 로그인 실패 카운터(`incrementLoginFail`/`isLoginLocked`/
> `resetLoginFail`, `auth:login_fail:{loginId}`)를 사용한다. 진입 시 `isLoginLocked()`가
> true면 `CustomException(ErrorCode.LOGIN_LOCKED)`를 즉시 throw하고, 비밀번호 불일치
> (`INVALID_PASSWORD`) 시 `incrementLoginFail()`을 호출한다(10분 내 5회 실패 시 잠금).
> 아이디 자체가 없는 경우(`USER_NOT_FOUND`)는 브루트포스 대상이 아니므로 카운트하지 않는다.
> 로그인에 최종 성공하면 `resetLoginFail()`로 카운터를 초기화한다.

> **동시 가입 경합 처리**: 소셜 로그인 신규 가입 분기는 조회 후 저장 구조라 동시 요청 시
> `social_accounts.uq_provider` 유니크 제약 위반(`DataIntegrityViolationException`)이 날 수 있다.
> `socialLogin()`은 이를 잡아 1회 재시도하는데, `processSocialLogin()`을 반드시 self 프록시
> (`@Autowired @Lazy` 필드, `OrderExecutionService`와 동일한 패턴)를 통해
> `@Transactional(REQUIRES_NEW)`로 호출한다 — self 없이 `this.processSocialLogin(...)`을 직접
> 재귀 호출하면 Spring AOP 프록시를 우회해 REQUIRES_NEW가 적용되지 않고, 방금 flush 실패로
> 손상된 트랜잭션/Hibernate Session을 재시도 시점에도 그대로 재사용하게 되어 재시도가 무의미해진다.

### 8-2. feature/auth-signup

| 구분 | 이름 |
|---|---|
| 엔드포인트 (AuthController 추가) | `POST /api/auth/signup`, `POST /api/auth/email/send-code`, `POST /api/auth/email/verify-code` |
| Service (AuthService 추가) | `signup(SignupRequest request)`, `sendEmailCode(String email)`, `verifyEmailCode(String email, String code)` |
| Request DTO | `SignupRequest`(loginId, password, name, email, birthdate, `role`, `adminCode`), `EmailCodeRequest`(email), `EmailCodeVerifyRequest`(email, code) |
| Response DTO | `SignupResponse`(userId, loginId, role) |
| Mail Client | `MailClient`(infra/mail) — `sendAuthCode(String toEmail, String code)`. Spring Mail(`JavaMailSender`) 사용, SMTP 설정은 `spring.mail.*`(환경변수 `MAIL_HOST`/`MAIL_PORT`/`MAIL_USERNAME`/`MAIL_PASSWORD`), 발신자 주소는 별도 커스텀 프로퍼티 `app.mail.from`(환경변수 `MAIL_FROM`). 발송 실패 시 `CustomException(ErrorCode.EXTERNAL_API_ERROR)` |

> v8 추가: `SignupRequest.role`(기본값 `USER`)이 `ADMIN`이면 `adminCode`가 필수이며,
> `AuthService.signup()`에서 서버 환경변수 `ADMIN_SIGNUP_CODE`와 대조 후 불일치 시
> `CustomException(ErrorCode.INVALID_ADMIN_CODE)` throw. 일치해야만 `Role.ADMIN`으로 가입.

> v8 추가: `signup()`은 이메일 인증을 거치지 않은 이메일로는 가입할 수 없다.
> `verifyEmailCode()` 성공 시 `RedisAuthCodeService.markEmailVerified(email)`로
> `auth:email_verified:{email}`(TTL 30분) 마커를 남기고, `signup()`은 중복 아이디/이메일
> 체크와 관리자 코드 검증을 모두 통과한 뒤 `consumeEmailVerified(email)`로 이 마커를
> 확인·소비(1회용)한다. 마커가 없으면 `CustomException(ErrorCode.EMAIL_NOT_VERIFIED)` throw.

> 코드리뷰 반영 — `SignupRequest.password`에 `@MaxByteSize(max = 72)`(`global/util` 신규 —
> 아래 참고)를 추가했다. BCrypt는 72바이트를 넘는 입력을 뒷부분부터 잘라버리는데, 상한 검증이
> 없으면 그 사실을 모르는 사용자가 긴 비밀번호를 입력해도 가입 자체는 성공해버려 뒷부분이
> 조용히 무시된 채로 해시·저장된다. 처음에는 `@Size(max = 72)`를 썼는데, `@Size`는 "글자 수"
> 기준이라 한글처럼 UTF-8에서 3바이트를 차지하는 멀티바이트 문자가 섞이면 글자 수는 72 미만인데
> 실제 바이트 수는 72를 넘어 여전히 잘리는 경우를 못 막았다. 그래서 `global/util`에 커스텀 Bean
> Validation 제약 `MaxByteSize`(어노테이션) + `MaxByteSizeValidator`(`ConstraintValidator`
> 구현체)를 새로 추가해 문자 수 대신 실제 바이트 수(`String.getBytes(charset).length`, 기본
> UTF-8)로 검증하도록 바꿨다. `SecurityUtil`과 마찬가지로 특정 도메인에 속하지 않는 범용
> 검증 로직이라 `global/util`에 둔다 — CLAUDE.md 4번 디렉토리 구조상 새 폴더는 아니다.

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
| 엔드포인트 (AccountController, OrderController, UserController 추가) | `GET /api/accounts/{accountId}/profit`, `GET /api/orders?accountId={accountId}`, `GET /api/orders/holdings?accountId={accountId}`, `GET /api/users/me`, `PATCH /api/users/me`, `POST /api/users/me/survey` |
| Service | `AccountService.getProfit(Long userId, Long accountId)`, `AccountService.getOwnedAccount(Long userId, Long accountId)`(계좌 소유권 검증 공용 메서드) / `OrderService.getMyOrderHistory(Long userId, Long accountId)`, `OrderService.getMyHoldings(Long userId, Long accountId)` / `HoldingValuationService.getHoldingValuations(Long accountId)`(보유종목+시세 평가 공용 메서드, domain.order.service 소속) / `UserService` — `getMyInfo(Long userId)`, `updateMyInfo(Long userId, UpdateUserRequest request)`, `saveSurvey(Long userId, SurveyRequest request)` |
| Response DTO | `ProfitResponse`(totalAsset, profitAmount, profitRate), `HoldingResponse`(stockCode, stockName, quantity, avgPrice, currentPrice, evaluationProfit), `UserInfoResponse`(userId, loginId, name, email, role, `status`), `InvestmentProfileResponse`(investmentTendency, fundTendency, investmentLevel) |
| Request DTO | `UpdateUserRequest`(name, email — 둘 다 `@NotBlank` 필수), `SurveyRequest`(answers: `List<Integer>`, investmentTendency, fundTendency) |

> **계좌 다중화 반영(원래 문서 초안은 계좌 1개 시절 기준이었음)**: `feature/mypage-account`부터
> 유저 1명이 계좌를 최대 3개까지 가질 수 있게 됐고, 계좌 A/B/C는 서로 완전히 독립된 영역이라
> (성향별로 나눠 투자 — 합산 개념이 없음) 수익률/주문내역/보유종목 전부 "유저의 전체 계좌 합산"이
> 아니라 "계좌 하나를 지정해서 그 계좌만" 조회하는 것으로 확정한다. `getProfit`/
> `getMyOrderHistory`/`getMyHoldings` 모두 시작 지점에서 `AccountService.getOwnedAccount(Long
> userId, Long accountId)`로 소유권을 검증한다(없으면 `ACCOUNT_NOT_FOUND`) — 코드리뷰에서
> `accountRepository.findByAccountIdAndUserId(...).orElseThrow(...)`가 `AccountService.
> getProfit()`과 `OrderService`의 4개 메서드(`createMarketOrder`/`createLimitOrder`/
> `getMyOrderHistory`/`getMyHoldings`)에 걸쳐 복붙되어 있던 것이 지적돼 `getOwnedAccount()`
> 하나로 모았다. `OrderService`는 이제 `AccountRepository`를 직접 주입받지 않고
> `AccountService`를 주입받아 이 메서드를 호출한다.
>
> **평가손익 계산 시 시세 캐시 미스 처리**: `stock:price:{stockCode}` 캐시(TTL 5초)는 장 마감
> 등으로 tick이 끊기면 비어있을 수 있다. 문의(inquiries) 없이 바로 볼 수 있는 마이페이지 조회
> 화면이 캐시 미스 하나 때문에 전체가 503(`STOCK_PRICE_NOT_AVAILABLE`)으로 죽으면 안 되므로,
> `getProfit()`/`getMyHoldings()`는 캐시가 비어있는 종목은 `avgPrice`로 대체해 평가손익을 0으로
> 표시한다(현재가 주문 체결처럼 정확한 실시간가가 반드시 필요한 경로와는 성격이 다르다). 이
> 폴백 자체는 `Holding.resolveValuationPrice(Long currentPrice)`(1-1 항목) 하나에 모아두고,
> `AccountService`/`OrderService`는 호출부에서 캐시 조회 결과(있으면 현재가, 없으면 null)만
> 넘긴다 — order 도메인 엔티티가 stock 도메인의 DTO 타입에 직접 의존하지 않도록 하기 위함.
>
> **`HoldingValuationService` 추출 (코드리뷰 반영)**: "보유종목 조회 → 시세 배치 조회
> (`RedisStockCacheService.getStockPrices(Collection<String> stockCodes)`, 7번 항목 — Redis
> MGET 한 번으로 종목마다 순차 호출을 피함, 캐시 미스 종목은 반환 Map에서 키 자체가 빠짐) →
> `Holding.resolveValuationPrice()`로 평단가 대체" 절차를 `AccountService.getProfit()`과
> `OrderService.getMyHoldings()`가 각자 복붙하고 있었다. 이 절차를 `domain.order.service.
> HoldingValuationService.getHoldingValuations(Long accountId)`(반환: `List<HoldingValuationDto>`)
> 하나로 모으고, `AccountService`는 더 이상 `HoldingRepository`/`RedisStockCacheService`를
> 직접 주입받지 않고 이 서비스(order 도메인 소속)를 통해서만 접근한다 — 이전에는 "거래는
> 하나의 강하게 결합된 집계"라는 이유로 account 도메인이 order 도메인의 Repository를 직접
> 참조했는데, 코드리뷰에서 CLAUDE.md 5번 규칙("도메인 간 직접 참조 대신 서비스 계층을 통해
> 호출")과 어긋난다는 지적을 받아 정리했다.
>
> **`HoldingValuationDto`(코드리뷰 반영, `domain.order.dto`)**: `stockCode, stockName, quantity,
> avgPrice, currentPrice` 필드를 갖는 레코드. 처음엔 이름이 `HoldingValuation`(Dto 접미사
> 없음)이었고 `Holding` 엔티티를 통째로 담고 있었는데, 코드리뷰에서 두 가지가 지적됐다 —
> ① CLAUDE.md 5번 규칙의 "내부 DTO는 XxxDto" 이름 규칙 위반, ② account 도메인이
> `HoldingValuationService`를 거치고도 여전히 `Holding` 엔티티의 메서드(`getQuantity()` 등)를
> 직접 호출해 도메인 경계를 넘는 목적이 절반만 달성됨. 정적 팩토리 `HoldingValuationDto.
> of(Holding holding, Long currentPrice)`로 엔티티에서 필요한 값만 꺼내 담도록 정리했고,
> `HoldingResponse.of(Holding, long)`도 `HoldingResponse.of(HoldingValuationDto)`로 바꿔
> 같은 라운드에 추가된 두 DTO가 동일하게 정적 팩토리 메서드를 쓰도록 맞췄다.
>
> **엔티티 메서드 추가**: `User.updateInfo(String name, String email)`(1-1 항목),
> `InvestmentProfile.updateSurvey(int investmentTendency, int fundTendency, String surveyAnswers)`
> (investmentLevel은 이 설문에서 바꾸지 않는다 — 별도 평가 로직은 이번 범위 밖),
> `Holding.resolveValuationPrice(Long currentPrice)`(위 항목 참고).
>
> **코드리뷰 반영 (버그 수정)**: `UpdateUserRequest.email`을 `@NotBlank`로 필수화했다 —
> 원래 선택 필드였는데, 생략(null) 시 `User.updateInfo()`가 그대로 반영해 기존 이메일을
> 지워버리는 문제가 있었다(카카오 무동의 가입자처럼 이메일이 원래 null인 계정 제외하고는 항상
> 값을 채워 보내야 한다). `updateMyInfo()`의 이메일 동일 여부 비교는 `equalsIgnoreCase`를
> 쓴다 — DB 콜레이션(`utf8mb4_unicode_ci`)이 대소문자를 구분하지 않아, `equals`로 비교하면
> 본인이 대소문자만 바꿔 재입력했을 때 `existsByEmail`이 자기 자신과 매칭돼 `DUPLICATE_EMAIL`을
> 잘못 던지는 문제가 있었다. `saveSurvey()`의 최초 제출 저장은 `HoldingSettlementService`와
> 동일한 패턴으로 `DataIntegrityViolationException`(uq_user_profile 위반)을 잡아
> `OPTIMISTIC_LOCK_CONFLICT`로 변환한다(동시 이중 제출 경합 대응). `GlobalExceptionHandler`에
> `MissingServletRequestParameterException`/`MethodArgumentTypeMismatchException` 핸들러를
> 추가해 `accountId` 쿼리 파라미터 누락/타입 불일치 시 500 대신 400으로 응답한다.

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

> 코드리뷰 반영 — `notify()`는 대부분 `OrderService.createMarketOrder()`/`OrderExecutionService.execute()`
> 등 호출 측의 `@Transactional` 안에서 참여 트랜잭션으로 호출된다. DB 저장(`notificationRepository.save()`)은
> 그 트랜잭션에 그대로 맡겨 함께 롤백되게 두지만, STOMP 유니캐스팅(`messagingTemplate.convertAndSendToUser()`)은
> `TransactionSynchronizationManager.registerSynchronization()`으로 등록한 `afterCommit()` 콜백에서만
> 실행한다 — `OrderService.registerAfterCommit()`(8-5, Redis pending order 반영을 커밋 후로 미루는 것과
> 동일한 목적·동일한 패턴)의 private 헬퍼를 `NotificationService`에도 그대로 복제했다. 커밋 전에 STOMP를
> 먼저 보내면 클라이언트가 알림을 받자마자 관련 데이터를 조회해도 아직 커밋 전이라 안 보일 수 있고, 이후
> 트랜잭션이 롤백돼도 이미 나간 STOMP는 취소할 수 없기 때문이다. 트랜잭션 동기화가 비활성 상태(단위
> 테스트 등)면 기존 헬퍼와 동일하게 즉시 실행으로 대체한다.

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
