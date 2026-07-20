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
| `Account` | `accountId`, `user`, `accountNumber`, `openedAt`, `baseBalance`, `balance`, `frozenBalance`, `version`, `status`, `createdAt` |
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

### 1-2. Repository 인터페이스 및 메서드

| Repository | 메서드 |
|---|---|
| `UserRepository` | `findByLoginId(String loginId)`, `findByEmail(String email)`, `existsByLoginId(String loginId)`, `existsByEmail(String email)`, `findByUserIdAndIsActiveTrue(Long userId)`, `countByIsActiveTrue()`(관리자 대시보드 — 총 사용자 수) |
| `SocialAccountRepository` | `findByProviderAndProviderId(SocialProvider provider, String providerId)`, `deleteByUserId(Long userId)` |
| `InvestmentProfileRepository` | `findByUserId(Long userId)`, `deleteByUserId(Long userId)` |
| `AccountRepository` | `findByUserId(Long userId)`, `findByAccountNumber(String accountNumber)`, `deleteByUserId(Long userId)` |
| `HoldingRepository` | `findAllByAccountId(Long accountId)`, `findByAccountIdAndStockCode(Long accountId, String stockCode)` |
| `OrderRepository` | `findAllByStockCodeAndStatus(String stockCode, OrderStatus status)`, `findAllByAccountIdOrderByOrderedAtDesc(Long accountId)`, `findByOrderIdAndAccountId(Long orderId, Long accountId)`, `findAllByStatusWithAccountAndUser(OrderStatus status)`, `countByStatus(OrderStatus status)`(관리자 대시보드 — 총 거래건수), `findTop20ByStatusOrderByExecutedAtDesc(OrderStatus status)`(관리자 대시보드 — 최근 거래 20건), `findAllOrdersWithUser(Pageable pageable)`(관리자 전체 거래 목록, `@Query` JOIN FETCH account.user), `findOrderWithUserById(Long orderId)`(관리자 거래 상세, `@Query` JOIN FETCH), `sumExecutedAmount()`(관리자 대시보드 — 총 거래대금, `@Query SUM(execPrice*quantity)`) |
| `WatchlistRepository` | `findAllByUserId(Long userId)`, `existsByUserIdAndStockCode(Long userId, String stockCode)`, `deleteByUserIdAndStockCode(Long userId, String stockCode)`, `deleteByUserId(Long userId)` |
| `AiPlanningSessionRepository` | `findAllByUserIdOrderByUpdatedAtDesc(Long userId)`, `findByUserIdAndSessionId(Long userId, Long sessionId)`, `deleteByUserId(Long userId)` |
| `AiPlanningMessageRepository` | `findAllBySessionIdOrderByCreatedAtAsc(Long sessionId)` |
| `SimulationRepository` | `findAllByUserIdOrderByCreatedAtDesc(Long userId)`, `findByUserIdAndSimulationId(Long userId, Long simulationId)`, `deleteByUserId(Long userId)` |
| `RecentViewedRepository` | `findAllByUserIdOrderByViewedAtDesc(Long userId)`, `findByUserIdAndStockCode(Long userId, String stockCode)`, `deleteByUserId(Long userId)` |
| `NotificationRepository` | `findAllByUserIdOrderByCreatedAtDesc(Long userId)`, `countByUserIdAndIsReadFalse(Long userId)`, `findByNotiIdAndUserId(Long notiId, Long userId)` |
| `InquiryRepository` | `findAllByUserIdOrderByCreatedAtDesc(Long userId)`(사용자 본인 문의 목록), `findByInquiryIdAndUserId(Long inquiryId, Long userId)`(본인 문의 상세, 소유권 검증), `findAllByOrderByStatusAscCreatedAtDesc()`(관리자 전체 목록 — PENDING이 알파벳순 앞이라 미답변 우선 정렬), `deleteByUserId(Long userId)`(탈퇴 처리용) |

> `deleteByUserId`는 탈퇴 로직(문서 하단 8-3 참고)에서 공통으로 쓰인다. v8부터 `InquiryRepository.deleteByUserId`도 동일하게 탈퇴 처리 순서에 포함한다.

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

### 2-3. 예외/핸들러
- `CustomException(ErrorCode errorCode)`, `CustomException(ErrorCode errorCode, Throwable cause)`
- `GlobalExceptionHandler` 메서드: `handleCustomException(CustomException e)`, `handleValidationException(MethodArgumentNotValidException e)`, `handleNoResourceFoundException(NoResourceFoundException e)`, `handleException(Exception e)`

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
| Request DTO | `CreateOrderRequest`(stockCode, orderType, quantity, priceType, orderPrice) |
| Response DTO | `CreateOrderResponse`(orderId, stockCode, execPrice, quantity, status) |

> v8 추가: `OrderService.createMarketOrder()` 진입 시 `account.getStatus() == AccountStatus.SUSPENDED`면
> `CustomException(ErrorCode.ACCOUNT_SUSPENDED)` throw (order-limit도 동일 적용).

### 8-6. feature/order-limit

| 구분 | 이름 |
|---|---|
| 엔드포인트 (OrderController 추가) | `POST /api/orders` (priceType=LIMIT 공용), `DELETE /api/orders/{orderId}` |
| Service (OrderService 추가) | `createLimitOrder(Long userId, CreateOrderRequest request)`, `cancelOrder(Long userId, Long orderId)` |
| Execution Service | `OrderExecutionService` — `execute(PendingOrderDto pendingOrder, long currentPrice)`, `checkAndExecute(String stockCode, long currentPrice)` |
| Response DTO | `OrderHistoryResponse`(orderId, stockCode, stockName, orderType, priceType, orderPrice, execPrice, quantity, status, orderedAt, executedAt) |

### 8-7. feature/mypage-account

| 구분 | 이름 |
|---|---|
| Controller | `AccountController`, `WatchlistController`, `RecentViewedController` |
| 엔드포인트 | `GET /api/accounts/me`, `POST /api/accounts/me/reset`, `GET /api/watchlist`, `POST /api/watchlist`, `DELETE /api/watchlist/{stockCode}`, `GET /api/recent-viewed`, `POST /api/recent-viewed` |
| Service | `AccountService` — `getMyAccount(Long userId)`, `resetBalance(Long userId, ResetBalanceRequest request)` / `WatchlistService` — `getMyWatchlist(Long userId)`, `addWatchlist(Long userId, String stockCode)`, `removeWatchlist(Long userId, String stockCode)` / `RecentViewedService` — `getMyRecentViewed(Long userId)`, `recordView(Long userId, String stockCode)` |
| Request DTO | `ResetBalanceRequest`(resetAmount) |
| Response DTO | `AccountInfoResponse`(accountNumber, balance, frozenBalance, baseBalance, `status`), `WatchlistResponse`(stockCode, stockName, addedAt), `RecentViewedResponse`(stockCode, stockName, viewedAt) |

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
