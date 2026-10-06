# CLAUDE.md — AI STOCK 백엔드 프로젝트 규칙

이 문서는 Claude Code가 AI STOCK 백엔드 작업 시 반드시 따라야 하는 규칙이다.
아래 규칙과 다른 코드를 절대 생성하지 않는다.

---

## 1. 프로젝트 개요

- **서비스**: AI STOCK — 모의투자 + AI 재무설계 + AI 맞춤 시황 브리핑 + 목표 도달 시뮬레이션 + 관리자 페이지
- **팀**: Team FP (Finance Planner)
- **베이스 패키지**: `com.teamfp.aistock` (전부 소문자)
- **Gradle 프로젝트 루트**: `aistock/` (저장소 루트가 아님 — IntelliJ에서 열 때 aistock/ 폴더를 Gradle 프로젝트로 열 것)

---

## 2. 기술 스택

- **백엔드**: Spring Boot 4.0.6 (Java 21), JPA, Spring Security, WebSocket/STOMP
- **DB**: MySQL (AWS RDS), Redis (AWS ElastiCache)
- **외부 API**: 외부 시세 데이터 제공사 OpenAPI(WebSocket 시세), Gemini API, Open DART, 네이버 뉴스 검색 API(NCP API Hub, AI 재무설계 상담 뉴스 검색), OAuth(카카오/네이버/구글)
  (Tavily는 뉴스 검색 백엔드로 쓰다가 2026-08-05 네이버로 교체, 관련 코드·설정은 2026-08-06 완전 삭제됨)
- **인프라**: AWS EC2, AWS Parameter Store, Docker Compose(로컬)
- **빌드**: Gradle

---

## 3. 빌드 / 실행 / 테스트 명령어

```bash
# 로컬 개발 환경 기동 (MySQL + Redis)
cd aistock
docker compose up -d

# 빌드
./gradlew build          # Mac/Linux
gradlew.bat build        # Windows

# 실행 (dev 프로필)
./gradlew bootRun --args='--spring.profiles.active=dev'

# 테스트
./gradlew test
```

- Java 버전은 **21로 통일** (build.gradle toolchain과 CI 워크플로우 모두 21)
- CI: `.github/workflows/backend-ci.yml` — working-directory는 `aistock`
- 민감한 값(JWT_SECRET, API 키)은 `.env` 또는 IDE 환경변수로 주입. 코드·yml에 하드코딩 금지.

### 시세 데이터 모드 설정 (팀원 로컬 환경, `aistock/.env`)

| 상황 | `.env` 설정 | 결과 |
|---|---|---|
| LS 키만 있고 local-market-data-generator가 없음 | `MARKET_DATA_MODE=real` | 시세·차트·순위를 LS API에서 직접 받음. 홈 주요 종목(`all=true`)도 등록 종목 105개 |
| generator를 함께 띄움(mock, 기본값) | `MARKET_DATA_MODE` 미지정 또는 `mock` | 시세·순위는 generator의 `market_data.json`. 차트는 합성 시세라 **목표 도달 시뮬레이션 수익률이 0% 근처로 나온다** |
| mock인데 폴더 배치가 기본과 다름 | `MARKET_DATA_PATH=<generator의 output 폴더 절대경로>` | 기본값 `../../local-market-data-generator/output` 대신 지정 경로를 읽음 |

- generator는 git에 포함되지 않은 별도 프로젝트다. mock 모드로 쓰려면 `AI-STOCK_Backend`와 같은 상위 폴더에
  `local-market-data-generator`를 두고, generator `.env`의 `OUTPUT_DIR`도 같은 `output` 폴더를 가리키게 한다
  (generator의 `OUTPUT_DIR` 기본값은 `output/`이 아니라 `local-market-data/`다).
- generator가 없거나 경로가 틀려도 서버는 기동된다 — 홈 종목 목록이 비어 있으면 경로부터 확인한다.
- 예시 (Windows, 기본 폴더 배치가 아닐 때): `MARKET_DATA_PATH=D:\work\local-market-data-generator\output`

---

## 4. 디렉토리 구조 (고정 — 임의 변경 금지)

```
com.teamfp.aistock
├── domain
│   ├── auth          → controller, service, dto
│   ├── user          → controller, service, repository, entity, dto
│   ├── account       → controller, service, repository, entity, dto
│   ├── stock         → controller, service, repository, entity, dto
│   ├── order         → controller, service, repository, entity, dto
│   ├── ai            → controller, service, repository, entity, dto
│   ├── notification  → controller, service, repository, entity, dto
│   ├── inquiry       → controller, service, repository, entity, dto
│   │                    (사용자 문의 작성·조회 — InquiryController)
│   └── admin         → controller, service, dto
│                        (관리자 전용 API. 별도 entity/repository 없이
│                         기존 도메인의 Repository를 주입받아 재사용:
│                         AdminDashboardController, AdminTradeController,
│                         AdminAccountController, AdminUserController,
│                         AdminInquiryController)
├── global
│   ├── config        → SecurityConfig, RedisConfig, WebSocketConfig, AsyncConfig, JpaConfig, AwsParameterStoreConfig
│   ├── security       → JwtProvider, JwtAuthenticationFilter, CustomUserDetailsService
│   ├── stomp          → StompAuthInterceptor
│   ├── exception      → CustomException, ErrorCode, GlobalExceptionHandler
│   ├── response       → ApiResponse
│   ├── redis          → RedisTokenService, RedisAuthCodeService, RedisStockCacheService,
│   │                     RedisPendingOrderService, RedisRateLimiterService, RedisOnlineStatusService,
│   │                     RedisAiToolCacheService, RedisPendingSimulationService
│   └── util           → DateUtil, SecurityUtil, ExternalApiInvoker, NewsRelevanceMatcher
├── infra
│   ├── ls            → MarketDataWebSocketClient, MarketDataWebSocketHandler, MarketDataReconnectService, dto
│   ├── gemini        → GeminiApiClient, dto
│   ├── dart          → DartApiClient, dto
│   ├── naver         → NaverNewsApiClient, dto (뉴스 검색 — infra/oauth의 NaverOAuthClient와는
│   │                    별개, feature/ai-planning이 뉴스 조회에 사용)
│   ├── oauth         → KakaoOAuthClient, NaverOAuthClient, GoogleOAuthClient, dto
│   └── mail          → MailClient (이메일 인증코드 발송, Spring Mail 사용)
└── resources
    ├── application.yml
    ├── application-dev.yml
    └── application-prod.yml
```

- 새 파일은 반드시 위 구조의 해당 위치에 생성한다.
- 외부 API 호출 코드는 `infra`에만 작성하고, `domain` 서비스는 infra 클라이언트를 주입받아 사용한다.
- 도메인 간 직접 참조 대신 서비스 계층을 통해 호출한다.
- `admin` 도메인은 자체 Entity/Repository를 두지 않고, `user`/`account`/`order`/`inquiry` 등 기존 도메인의 Repository를 그대로 주입받아 조합한다 (관리자 조회는 여러 도메인을 가로지르는 집계·조합 성격이라 중복 Repository를 만들지 않는다).

---

## 5. 코딩 컨벤션

### 네이밍 규칙

| 대상 | 규칙 | 예시 |
|---|---|---|
| 패키지명 | 전부 소문자 | `domain.user.entity` |
| 클래스명 | PascalCase | `UserService`, `OrderController` |
| 메서드명 | camelCase | `getUserInfo()`, `createOrder()` |
| 변수명 | camelCase | `userName`, `accessToken` |
| 상수명 | UPPER_SNAKE_CASE | `MAX_LOGIN_ATTEMPT` |
| Enum 이름 | PascalCase | `OrderStatus` |
| Enum 값 | UPPER_SNAKE_CASE | `PENDING`, `EXECUTED`, `CANCELLED` |
| DB 테이블 | snake_case | `social_accounts`, `ai_planning_messages` |
| DB 컬럼 | snake_case | `user_id`, `created_at`, `frozen_balance` |
| Boolean 컬럼 | is_/has_ prefix | `is_read`, `is_active` |
| Boolean 변수 | is/has/can/should | `isLoggedIn`, `hasPermission` |

### 클래스 접미사 규칙

| 계층 | 형식 | 예시 |
|---|---|---|
| Controller | XxxController | `AuthController` |
| Service | XxxService | `OrderService` |
| Repository | XxxRepository | `UserRepository` |
| Entity | 도메인명 그대로 | `User`, `Order`, `Holding` |
| 요청 DTO | XxxRequest | `LoginRequest`, `CreateOrderRequest` |
| 응답 DTO | XxxResponse | `LoginResponse`, `UserInfoResponse` |
| 내부 DTO | XxxDto | `StockPriceDto`, `PendingOrderDto` |

- `UserDto`, `DataDto`, `ResultDto` 같은 모호한 이름 금지
- `data`, `info`, `temp`, `test`, `aaa` 같은 의미 없는 변수명 금지
- 배열/리스트 변수는 복수형: `users`, `stockOrders`

### Lombok / 코드 스타일

- 기본 적용: `@Getter`, `@Builder`, `@NoArgsConstructor(access = AccessLevel.PROTECTED)`, `@RequiredArgsConstructor`
- `@Setter`, `@Data` 사용 금지
- Entity에 `@Setter` 금지 — 상태 변경은 의미 있는 메서드로 작성
- DTO ↔ Entity 변환은 DTO의 정적 팩토리 메서드(`from()`, `of()`) 또는 Entity의 `toEntity()`로 처리
- 트랜잭션: 조회는 `@Transactional(readOnly = true)`, 변경은 `@Transactional`

### REST API URL 규칙

- 명사 중심, 동작은 HTTP Method로 표현, kebab-case 또는 복수 명사

```
GET    /api/users/me
PATCH  /api/users/me
POST   /api/orders
DELETE /api/orders/{orderId}
GET    /api/stocks/{stockCode}
GET    /api/inquiries
PATCH  /api/admin/inquiries/{inquiryId}/answer
```

- 예외: 인증 API는 행위 URL 허용 (`POST /api/auth/login`, `/logout`, `/refresh`)
- `/api/getUser`, `/api/createOrder` 같은 동사형 URL 금지
- 관리자 전용 API는 `/api/admin/**` 하위에 두고, `SecurityConfig`에서 `hasRole("ADMIN")`로 제한

### 공통 응답 및 예외 처리

- 모든 API 응답은 `ApiResponse<T>` 포맷: `{ success, message, data }`
- 비즈니스 예외는 `CustomException` + `ErrorCode` Enum으로 처리
- 예외는 `GlobalExceptionHandler`(@RestControllerAdvice)에서 일괄 처리
- Controller에서 try-catch 남발 금지 — 예외는 던지고 핸들러가 처리

---

## 6. DB 설계 준수 사항 (MySQL v8 기준 — 13개 테이블)

`users`, `social_accounts`, `investment_profile`, `accounts`, `holdings`, `orders`,
`watchlist`, `ai_planning_sessions`, `ai_planning_messages`, `simulations`,
`recent_viewed`, `notifications`, `inquiries`

- `users.email`은 **NULL 허용** (카카오 이메일 동의 거부 대응)
- 소셜 로그인 정보는 `social_accounts` 별도 테이블 (users 1:N)
- `accounts.version` — 낙관적 락 컬럼 (`@Version` 사용)
- `accounts.frozen_balance` — 지정가 주문 예약 금액
- `accounts.base_balance` — 수익률 계산 기준값
- `orders.status` — `PENDING` / `EXECUTED` / `CANCELLED`
- `users.status` — `ACTIVE` / `SUSPENDED` — **관리자에 의한 로그인 차단**. 기존
  `is_active`/`deleted_at`(본인 탈퇴)과는 별개 개념이니 혼동하지 않는다.
- `accounts.status` — `ACTIVE` / `SUSPENDED` — **관리자에 의한 계좌 거래 정지**.
  로그인은 가능하되 매수/매도 주문만 차단한다 (조회는 계속 가능).
- `inquiries` — 사용자 문의 + 관리자 답변을 한 테이블에서 관리 (`status`: `PENDING`/`ANSWERED`).
  `answered_by`는 답변한 관리자 `user_id`를 참조하며 `ON DELETE SET NULL`
  (관리자 탈퇴 시에도 문의 기록은 보존).
- Entity에는 JPA Auditing으로 `created_at`, `updated_at` 자동 처리 (`JpaConfig`)
- 삭제는 soft delete(`deleted_at`) 원칙 (users)
- **주의**: users의 soft delete는 FK `ON DELETE CASCADE`를 발동시키지 않는다. 탈퇴 처리 시
  자식 테이블(social_accounts, investment_profile, accounts, watchlist,
  ai_planning_sessions, simulations, recent_viewed, notifications, inquiries)은
  탈퇴 서비스 로직에서 명시적으로 삭제해야 한다. (자세한 순서는 schema.sql의
  users 테이블 상단 주석 참고)

---

## 7. Redis 키 규칙 (임의 키 추가 금지)

| 키 | TTL | 용도 |
|---|---|---|
| `auth:refresh:{userId}` | 14일 | Refresh Token |
| `auth:blacklist:{accessToken}` | Access Token 남은 유효시간(동적) | 로그아웃 블랙리스트 |
| `auth:email_code:{email}` | 5분 | 이메일 인증코드 |
| `auth:email_verified:{email}` | 30분 | 이메일 인증 완료 마커 (`signup()`이 소비 후 삭제하는 1회용) |
| `auth:login_fail:{loginId}` | 10분 | 로그인 실패 카운터 (5회 잠금) |
| `auth:admin_code_fail:{initial \| user:{userId}}` | 10분 | 관리자 인증 코드 틀린 횟수 (3회 잠금 — 최초 관리자 생성은 `initial` 하나로 합쳐서, 관리자 계정 폐기는 계정별. feat/admin-improvements, 2026-10-06) |
| `stock:price:{stockCode}` | 5초 | 현재가 캐시 |
| `stock:hoga:{stockCode}` | 2초 | 호가 캐시 |
| `pending:orders:{stockCode}` | 없음 | 지정가 미체결 주문 |
| `gemini:rate:{userId}:minute` | 1분 | Gemini Rate Limiter (분당 3회) — SimulationService만 사용, AI 재무설계사는 2026-09-21부터 미적용 |
| `gemini:rate:{userId}:daily` | 1일 | Gemini Rate Limiter (일일 10회) — SimulationService만 사용, AI 재무설계사는 2026-09-21부터 미적용 |
| `simulation:pending:{userId}:{pendingSimulationId}` | 30분 | 목표 도달 시뮬레이션 실행 결과 임시 보관 — 저장 버튼을 눌러야 DB(simulations)에 기록 (`RedisPendingSimulationService`, 2026-10-01) |
| `admin:online:users` | 없음 (이벤트 기반) | 관리자 대시보드 — 온라인 사용자 집합 (WebSocket CONNECT/DISCONNECT 시 갱신) |
| `ai:tool:{sessionId}:{도구이름}?{인자}` | 30분 | AI 상담 세션 내 DART/네이버 도구 실행 결과 캐시 (같은 조건 재조회 시 재사용) |

- Redis 접근은 반드시 `global/redis`의 **8개** 서비스 클래스를 통해서만 한다.
- 도메인 서비스에서 RedisTemplate 직접 주입 금지.

---

## 8. 아키텍처 필수 준수 사항

- **실시간 시세**: 외부 시세 데이터 제공사 WebSocket 수신 → Throttle 200ms → Redis 캐싱 + STOMP 브로드캐스팅 동시 처리
- **STOMP 토픽**: `/topic/stock/{stockCode}` (브로드캐스팅), `/user/{userId}/queue` (유니캐스팅)
- **비회원 실시간 시세**: `StompAuthInterceptor`는 토큰 없는 CONNECT를 익명 세션으로 허용하되,
  익명 세션에는 `/topic/stock/{stockCode}`·`/topic/stock/{stockCode}/hoga` 구독만 허용하고 SEND는
  막는다. 토큰이 있는데 무효면 여전히 거부한다(비회원 호가 제공, #06, 2026-09-25).
- **tick 처리**: `@Async` + 전용 스레드풀 (`AsyncConfig`)
- **지정가 체결**: tick 수신 시 `pending:orders` 확인 → 조건 충족 시 낙관적 락으로 체결.
  주문 접수 시에도 커밋 직후 현재가(`StockQuoteService`)로 체결 조건을 1회 확인한다
  (`OrderService.tryImmediateExecution()`, `OrderExecutionService.execute()`는 이 afterCommit 경로
  때문에 `REQUIRES_NEW`). 미체결 주문이 있는 종목은 `StockSubscriptionManager`의 주문 구독
  (`increaseOrderSubscription`/`decreaseOrderSubscription`)으로 체결·취소될 때까지 real/mock 모두
  구독을 유지한다(fix/realtime-trade-fix, 2026-09-30).
- **서버 시작 순서**: `@PostConstruct`로 DB PENDING 주문을 Redis에 재적재하고 구독 카운트를 복원한 뒤,
  `ApplicationReadyEvent`에서 real 모드 WebSocket의 최초 연결을 예약한다. 연결 전 등록한 종목은 연결 시 재구독한다.
- **외부 시세 데이터 재연결**: 지수 백오프 (1→2→4→최대 30초)
- **실전·모의 서버 불일치**: WebSocket 구독 응답 `10001`은 같은 설정으로 재시도해도 해결되지 않으므로
  연결을 끊고 재연결을 중지한다. API 키 종류와 `MARKET_DATA_WEBSOCKET_URL`(실전 9443/모의 29443)을
  맞춘 뒤 서버를 다시 시작한다.
- **외부 시세 데이터 REST 장애 처리**: `infra/marketdata`의 REST 클라이언트는 제공사 호출(토큰 발급 포함)이
  네트워크 오류·HTTP 오류로 실패하면 `CustomException(ErrorCode.MARKET_DATA_UNAVAILABLE)`(503)을 던진다
  (`MarketDataApiClientSupport.invokeMarketData()`). 빈 목록/`Optional.empty()`는 "제공사가 정상 응답했지만
  데이터가 없음"만 뜻하므로, 클라이언트에서 이 예외를 잡아 빈 값으로 삼키지 않는다. REST API는 이 예외를
  그대로 전파하고, `AiPlanningService`는 도구 결과를 일시 장애 전용 문구로 바꿔 Gemini에 넘긴다
  (외부 장애와 빈 목록 구분 처리, #05, 2026-09-24). mock 모드의 `LocalMarketDataReader`는 대상이 아니다.
- **외부 시세 데이터 mock 모드**: `market-data.mode=mock`이면 아래 경로들이 실제 외부 시세 데이터
  API·Redis 대신 `LocalMarketDataReader`로 데이터를 공급한다(순위·지수·ETF 시세·차트 mock 지원 추가,
  2026-09-21). `MarketDataAccessTokenProvider`와 이 목록에 없는 나머지 REST 메서드(t1105/t1305의
  `getRecentHistoricalPrices()`·t1404/t1405·t1486·t8407·`EtfApiClient.getConstituents()` 등)는
  여전히 `market-data.mode`와 무관하게 항상 실제 외부 시세 데이터 API를 호출한다.
  - `MarketDataApiClient.getCurrentPrice()`/`StockService.getCurrentPrice()`·`getHoga()` — 종목
    현재가·호가(가장 먼저 추가된 mock 경로).
  - `MarketDataApiClient.getHistoricalPrices(stockCode, periodMonths)` — 차트. market_data.json에는
    현재가 스냅샷 1건뿐이라 실제 과거 시세가 없다. 종목코드로 시드를 고정한 결정적 합성
    (fabricated) OHLC 시계열을 만들어 반환하며(같은 종목은 항상 같은 그래프), 가장 최근 구간의
    종가만 mock 현재가와 일치시킨다. **실제 과거 시세가 아니다.**
  - `HighItemApiClient.getTopVolume()`/`getTopTradingValue()`/`getTopPriceChangeRate()`/
    `getTopPriceDeclineRate()`/`getTopMarketCap()` — 순위(`MarketQueryService.getRankings()`가 노출하는
    5종). 상승/하락 순위는 real 모드에서 t1441을 코스피+코스닥 전체·당일 조건으로 호출하고, mock
    모드에서는 상승 종목만/하락 종목만 걸러 정렬한다(상승·하락 순위 전체 시장 기준, #13, 2026-09-30). 전종목이 아니라
    `LocalMarketDataReader.getAllCurrentPrices()`(stocks.json에 등록된 종목만, 2026-09-21 기준
    105개)를 정렬해 상위 10개만 뽑는 근사치다. 단 홈 주요 종목은 `GET /api/market/rankings?all=true`로
    `(int limit)` 오버로드(`HighItemApiClient.ALL_REGISTERED_STOCKS`)를 호출해 10개 제한 없이 등록 종목 전체를
    한 번에 받아 화면에서 15개씩 무한 스크롤로 보여준다(2026-10-01). 같은 클래스의 나머지 3개
    (`getSurgingVolumeVsYesterday()`/시간외 2종)는 mock 대상이 아니다.
  - **real 모드의 `all=true`(2026-10-02 수정)**: real 모드에서는 `all=true`여도 LS API 특성상(순위 TR
    t1441/t1444/t1452/t1463은 시장 전체 상위 목록만 줌) `MAX_RANKING_ITEMS=10`에서 잘렸으나, 이번 수정으로
    해제됐다. 이전에는 배포(prod=real) 환경에서 홈 무한 스크롤이 10개만 보이고, 시뮬레이션 리밸런싱 후보
    (`getRankings("market-cap", true)`)도 10개뿐이었다. 이제 `limit`이 10을 넘으면 순위 TR 대신
    `RegisteredStockReader`가 읽은 등록 종목 목록(`aistock/src/main/resources/stocks.json`, 105개)의 종목코드로
    `MarketDataApiClient.getMultiStockPricesInBatches()`(t8407, 50종목씩 3회, REST 캐시 10초)를 호출해 mock과 같은
    기준으로 정렬한다 — mock/real 모두 등록 종목 105개 범위의 순위다. 시가총액은 t8407에 없어 실시간 현재가 ×
    `stocks.json`의 상장주식수(`listingShares`, 천주, 2026-10-02 스냅샷)로 계산하고, 거래대금은 t8407 `value`(백만원,
    실제값)를 쓴다. `all=false`(기본, AI 상담 도구 포함)는 그대로 순위 TR 상위 10건이다. 백엔드
    `resources/stocks.json`과 local-market-data-generator의 `stocks.json`은 종목 구성이 같아야 하므로 종목을
    추가·삭제할 때 두 파일을 함께 고친다.
  - `IndustryApiClient.getCurrentPrice(marketName)` — 지수(코스피/코스닥). 실지수는 전종목 시가총액
    가중평균이라 105개 mock 종목으로 재현 불가능해, 고정 베이스값(`MOCK_BASE_INDEX_VALUE`)을 같은
    시장 mock 종목의 평균 등락률만큼 흔든 근사치를 쓴다. **실제 지수 값이 아니다.** 같은 클래스의
    `getTrend()`/`getExpectedIndex()`는 mock 대상이 아니다.
  - `EtfApiClient.getCurrentPrice()` — ETF 시세. stocks.json에 `isEtf: true`로 등록된 종목만
    mock 데이터가 있고(2026-09-21 기준 5개), 등록되지 않은 ETF 코드는 real 모드와 동일하게
    빈 값을 반환한다. ETF 종목에는 `exchgubun`("K"=KRX 고정값)도 함께 채워지며 별도 매핑
    없이 `CurrentPriceDetailDto` 그대로 반환된다 — 실제 t1901 API의 exchgubun 스펙과는 무관한
    mock 전용 필드다(ETF exchgubun 신규 필드 반영, #04, 2026-09-23).
  - 위 4개 mock 파생 로직이 쓰는 `market`(KOSPI/KOSDAQ)·`etf` 필드는 t1102 실제 응답에는 없는
    필드로, local-market-data-generator가 stocks.json의 로컬 메타데이터를 market_data.json에
    함께 써 넣는다(`CurrentPriceDetailDto.market`/`etf`, real 모드 파싱 경로에서는 채워지지 않음).
  - `MockMarketDataGenerator`(5초 주기 폴링, generator.py 기본 수집 주기 10초)는 `MarketDataWebSocketClient`(real 전용) 대신
    변경분을 감지해 STOMP로 실시간 브로드캐스트한다(현재가/호가 대상).
  `LocalMarketDataReader`는 원본을 파일 또는 HTTP 둘 중 하나에서 읽는다:
  - **파일 모드(로컬 개발 기본값)**: `market-data.url`이 비어있으면 `market-data.local-path`
    디렉토리의 `market_data.json` 단일 파일(종목코드를 키로, 현재가·호가 필드가 함께 들어있는
    맵, local-market-data-generator가 생성)을 직접 읽는다. dev 기본값은 `MARKET_DATA_PATH` 환경변수가 없을 때
    `../../local-market-data-generator/output`(백엔드 실행 디렉토리 `aistock/` 기준 상대경로 — `AI-STOCK_Backend`와
    `local-market-data-generator`가 같은 상위 폴더에 나란히 있다는 전제)이다. 이전 기본값
    `C:\AI-STOCK\...` 절대경로는 PC마다 경로가 달라 generator가 있어도 데이터를 못 읽는 문제가 있어 바꿨다
    (2026-10-02). 파일이 없거나(generator 미설치·미실행) 파싱에 실패해도 서버는 정상 기동하고 시세·순위만 빈 값으로
    응답한다.
  - **HTTP 모드(백엔드가 생성기와 파일 시스템을 공유 못 하는 배포 환경)**: 환경변수
    `MARKET_DATA_URL`을 설정하면 파일 대신 그 URL로 GET 요청해 같은 JSON을 가져온다.
    local-market-data-generator는 자체 HTTP 서버(기본 포트 8081)를 함께 띄워
    `GET /market-data`(market_data.json 원문), `GET /health`(생존 확인용)를 노출한다 — 이
    서버는 60초 주기 수집 루프와 무관하게 항상 켜져 있다(`HTTP_SERVER_ENABLED`로 끌 수 있음).
  - **배포 시 주의**: `application-prod.yml`은 `market-data.mode: real`이 고정값이라(환경변수 오버라이드
    없음) `prod` 프로필로는 mock 모드 자체를 켤 수 없다. mock 모드로 배포 서버를 띄워 이
    데이터 흐름을 검증하려면 반드시 `--spring.profiles.active=dev`로 실행해야 한다.
- **Gemini 호출 전** `RedisRateLimiterService` 통과 필요 — 단, **AI 재무설계사(`AiPlanningService`)는
  2026-09-21 사용자 요청으로 이 제한을 제거함**("몇 번 대화하다 짤리면 안 된다"는 이유, Gemini
  자체 API 한도에만 걸림). `SimulationService`(목표 도달 시뮬레이션)는 그대로 분당3/일일10
  제한을 적용받는다.
- **온라인 추적**: `StompAuthInterceptor`의 CONNECT/DISCONNECT 시점에 `RedisOnlineStatusService`로
  `admin:online:users` 갱신. 클라이언트가 비정상 종료해 DISCONNECT 프레임 없이 끊기는 경우를
  대비해 `SessionDisconnectEvent` 리스너로 보완 처리한다. 서버 자체가 비정상 종료(크래시)된 경우
  모든 WebSocket 연결이 함께 끊기므로, 서버 재시작 시 `@PostConstruct`로 `admin:online:users`를
  전체 삭제한다 (재적재 대상 DB가 없는 순수 이벤트성 데이터이므로 `RedisPendingOrderService`처럼
  DB 기준 재적재가 아니라 단순 초기화가 맞다).
- **관리자 계좌/사용자 정지 검사**: 주문 생성(`OrderService.createOrder()`) 진입 시
  `accounts.status`가 `SUSPENDED`면 `CustomException(ErrorCode.ACCOUNT_SUSPENDED)`를 던진다.
  로그인(`AuthService.login()`) 시 `users.status`가 `SUSPENDED`면 로그인 자체를 차단한다.
- **환경변수/시크릿**: AWS Parameter Store 사용, 코드에 API 키 하드코딩 절대 금지
- **관리자 가입**: 회원가입은 항상 `Role.USER`다(공개 API의 관리자 가입 경로는 feat/admin-improvements에서 제거).
  관리자가 한 명도 없을 때만 `POST /api/auth/initial-admin`으로 첫 관리자를 만들 수 있고, 이때 관리자 인증 코드를
  서버 환경변수 `ADMIN_SIGNUP_CODE`와 대조한다(`InitialAdminService`). 그 뒤 관리자는 관리자 페이지에서만 추가한다.
- **CORS**: 환경변수 `CORS_ALLOWED_ORIGINS`(콤마로 여러 도메인 구분, 기본값
  `http://localhost:3000`) 하나를 REST(`SecurityConfig.corsConfigurationSource()`)와
  WebSocket(`WebSocketConfig.registerStompEndpoints()`의 `setAllowedOriginPatterns()`) 둘 다
  똑같이 적용한다. 배포 시 프론트엔드 도메인을 한 곳에만 등록하면 된다.
- **프론트엔드 환경변수 파일**: `AI-STOCK_Frontend/.env.local`(로컬 개발, git 추적 안 함),
  `.env.production`(배포 빌드용, git 추적 안 함) — 둘 다 `NEXT_PUBLIC_API_BASE_URL`로 백엔드
  주소를 지정한다. `https://`로 넣으면 STOMP 연결(`lib/api/realtime.ts`)이 자동으로
  `wss://`로 바뀌므로 ws/wss를 별도 분기할 필요는 없다.

---

## 9. 브랜치 / 커밋 / PR 규칙

### 브랜치
- `main`(배포) ← `dev`(통합) ← `feature/*`, `fix/*`, `chore/*`, `refactor/*`, `docs/*`
- 브랜치는 항상 최신 `dev`에서 생성. 이전 기능 브랜치에서 파생 금지.
- 하나의 브랜치 = 하나의 작업 단위. 병합 완료된 브랜치는 삭제.

```bash
git checkout dev
git pull origin dev
git checkout -b feature/기능명
```

### 커밋 메시지
```
type: 작업 내용
```
- type: `feat` `fix` `refactor` `docs` `style` `test` `chore` `perf` `remove` `build` `revert`
- 예: `feat: 로그인 기능 구현`, `chore: spring web 의존성 추가`

### PR
- 제목: `[작성자명] [작업유형] 기능명` — 예: `[전우혁] [FEAT] 로그인`
- 작업유형: `FEAT` `FIX` `DESIGN` `REFACTOR` `DOCS` `CHORE` `TEST` `HOTFIX`
- base는 항상 `dev`. main 직접 merge 금지.
- merge는 레포 주인(PM)만 수행.
- Reviewer: PM 지정 / Assignee: 본인 지정

### PR 본문 양식
```markdown
## 작업 내용
-

## 사용자 기준 변경 내용
(예시) AI 재무설계사 기능에 마이페이지 보유 주식·투자성향 데이터를 반영해 맞춤형 분석 고도화
(예시) 시뮬레이션이 DART·뉴스 데이터를 추가 참조해 더 정밀한 결과 제공
-

## 변경된 파일
-

## 확인 사항
- [ ] 로컬에서 정상 실행 확인
- [ ] 관련 페이지 이동 확인
- [ ] API 연동 정상 동작 확인
- [ ] 오류 또는 경고 메시지 확인
- [ ] NAMING.md와 실제 코드(클래스/메서드/필드명) 일치 여부 확인

## 참고 사항
-
```

### 롤백(Revert)
- 병합된 커밋 문제 발생 시 `revert/문제기능명` 브랜치 생성 → `git revert` → PR
- 커밋: `revert: 커밋 되돌리기 내용` / PR 제목: `[작성자명] [HOTFIX] 기능명 되돌리기`
- force push, 히스토리 재작성 절대 금지

---

## 10. 주의사항 / 하지 말아야 할 것

- 이 문서에 없는 새 폴더·테이블·Redis 키가 필요하면 임의 생성하지 말고 먼저 사용자에게 확인한다.
- `.idea/`, `.env`, `build/` 등 .gitignore 대상 파일을 git에 추가하지 않는다.
- `main` 브랜치에 직접 커밋·merge하지 않는다.
- API 키·비밀번호를 코드나 yml에 하드코딩하지 않는다.
- Entity에 `@Setter`, `@Data`를 사용하지 않는다.
- 도메인 서비스에서 RedisTemplate·외부 API를 직접 호출하지 않는다 (반드시 global/redis, infra 경유).
- 부분 코드/생략 없이 완성된(바로 실행 가능한) 코드를 제공한다.
- 모든 응답과 코드 주석은 **한국어**로 작성한다.
- 테스트 코드는 로컬 검증용으로만 작성하고 지우지 않는다. 작성한 테스트는 `src/test`에 그대로 두고 커밋·PR에 포함한다.
- 다른 브랜치에서 이미 병합된 테스트 파일을 삭제하는 커밋이 포함되어 있으면 PR 작성자가 의도적으로 삭제한 이유를 PR 본문에 명시해야 하며, 그렇지 않으면 리뷰어는 병합을 보류하고 원인 확인을 요청한다.

---

## 11. 네이밍 카탈로그

- 전체 클래스명·메서드명·변수명·필드명·API 경로는 `NAMING.md`(이 문서와 같은 경로)를 따른다.
- 새 클래스·메서드·필드·엔드포인트가 필요하면 코드를 작성하기 전에 먼저
  `NAMING.md`에 추가한다. 문서에 없는 이름을 임의로 만들지 않는다.
- 작업 중 계획이 바뀌어 `NAMING.md`에 등록해둔 이름을 실제로는 쓰지 않게 됐다면,
  해당 PR 안에서 `NAMING.md`의 관련 항목도 함께 삭제한다. 코드와 `NAMING.md`가
  어긋난 상태로 `dev`에 병합하지 않는다.
- 리뷰어(PM)는 PR 리뷰 시 변경된 클래스·메서드·필드가 `NAMING.md`와 일치하는지
  함께 확인한다. 새 이름이 추가됐는데 문서 반영이 빠졌거나, 반대로 문서에는
  남아있는데 코드에서 삭제된 이름이 있으면 merge 전에 정정을 요청한다.
- 즉, `NAMING.md`는 "코딩 전 사전 등록 → 코딩 중 사용 → PR 시점에 실제 코드와
  동기화"의 흐름으로 항상 최신 상태를 유지한다. 정기적인 전수 스캔·일괄 정리는
  하지 않는다.

---

## 12. DB / Redis 설계 참고 문서

- DB 테이블 구조·컬럼·제약조건·인덱스는 `schema.sql`(이 문서와 같은 경로)을
  기준으로 한다. Entity를 작성하기 전 반드시 이 파일을 먼저 확인한다.
- Redis 키 설계·TTL 정책·서비스 클래스 구조는 `redis-logic.md`(이 문서와 같은
  경로)를 기준으로 한다. `global/redis`, `global/config`의 Redis 관련 클래스를
  작성하기 전 반드시 이 파일을 먼저 확인한다.
- 두 문서에 없는 테이블·컬럼·제약조건·Redis 키가 필요하면 임의로 만들지 말고
  먼저 팀에 확인한 뒤 해당 문서에 반영한다.
- 실제 구현이 진행된 이후에는 `domain/*/entity`, `global/redis`,
  `global/config/RedisConfig` 등 실제 소스 코드가 최신 상태의 기준이 된다.
  `schema.sql`/`redis-logic.md`는 그 이후로는 설계 근거 문서로 취급하되,
  실제 스키마·Redis 로직이 변경되면 두 문서도 함께 갱신하여 코드와 문서가
  어긋나지 않도록 한다 (NAMING.md와 동일한 원칙).
