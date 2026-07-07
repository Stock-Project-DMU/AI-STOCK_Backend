# CLAUDE.md — AI STOCK 백엔드 프로젝트 규칙

이 문서는 Claude Code가 AI STOCK 백엔드 작업 시 반드시 따라야 하는 규칙이다.
아래 규칙과 다른 코드를 절대 생성하지 않는다.

---

## 1. 프로젝트 개요

- **서비스**: AI STOCK — 모의투자 + AI 재무설계 + AI 맞춤 시황 브리핑 + 목표 도달 시뮬레이션
- **팀**: Team FP (Finance Planner)
- **베이스 패키지**: `com.teamfp.aistock` (전부 소문자)
- **Gradle 프로젝트 루트**: `aistock/` (저장소 루트가 아님 — IntelliJ에서 열 때 aistock/ 폴더를 Gradle 프로젝트로 열 것)

---

## 2. 기술 스택

- **백엔드**: Spring Boot 3.x (Java 21), JPA, Spring Security, WebSocket/STOMP
- **DB**: MySQL (AWS RDS), Redis (AWS ElastiCache)
- **외부 API**: LS증권 OpenAPI(WebSocket 시세), Gemini API, Open DART, Tavily, OAuth(카카오/네이버/구글)
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
│   └── notification  → controller, service, repository, entity, dto
├── global
│   ├── config        → SecurityConfig, RedisConfig, WebSocketConfig, AsyncConfig, JpaConfig, AwsParameterStoreConfig
│   ├── security      → JwtProvider, JwtAuthenticationFilter, CustomUserDetailsService
│   ├── stomp         → StompAuthInterceptor
│   ├── exception     → CustomException, ErrorCode, GlobalExceptionHandler
│   ├── response      → ApiResponse
│   ├── redis         → RedisTokenService, RedisAuthCodeService, RedisStockCacheService, RedisPendingOrderService, RedisRateLimiterService
│   └── util          → DateUtil, SecurityUtil
├── infra
│   ├── ls            → LsWebSocketClient, LsWebSocketHandler, LsReconnectService, dto
│   ├── gemini        → GeminiApiClient, dto
│   ├── dart          → DartApiClient, dto
│   ├── tavily        → TavilyApiClient, dto
│   └── oauth         → KakaoOAuthClient, NaverOAuthClient, GoogleOAuthClient, dto
└── resources
    ├── application.yml
    ├── application-dev.yml
    └── application-prod.yml
```

- 새 파일은 반드시 위 구조의 해당 위치에 생성한다.
- 외부 API 호출 코드는 `infra`에만 작성하고, `domain` 서비스는 infra 클라이언트를 주입받아 사용한다.
- 도메인 간 직접 참조 대신 서비스 계층을 통해 호출한다.

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
```

- 예외: 인증 API는 행위 URL 허용 (`POST /api/auth/login`, `/logout`, `/refresh`)
- `/api/getUser`, `/api/createOrder` 같은 동사형 URL 금지

### 공통 응답 및 예외 처리

- 모든 API 응답은 `ApiResponse<T>` 포맷: `{ success, message, data }`
- 비즈니스 예외는 `CustomException` + `ErrorCode` Enum으로 처리
- 예외는 `GlobalExceptionHandler`(@RestControllerAdvice)에서 일괄 처리
- Controller에서 try-catch 남발 금지 — 예외는 던지고 핸들러가 처리

---

## 6. DB 설계 준수 사항 (MySQL v6 기준 — 12개 테이블)

`users`, `social_accounts`, `investment_profile`, `accounts`, `holdings`, `orders`,
`watchlist`, `ai_planning_sessions`, `ai_planning_messages`, `simulations`,
`recent_viewed`, `notifications`

- `users.email`은 **NULL 허용** (카카오 이메일 동의 거부 대응)
- 소셜 로그인 정보는 `social_accounts` 별도 테이블 (users 1:N)
- `accounts.version` — 낙관적 락 컬럼 (`@Version` 사용)
- `accounts.frozen_balance` — 지정가 주문 예약 금액
- `accounts.base_balance` — 수익률 계산 기준값
- `orders.status` — `PENDING` / `EXECUTED` / `CANCELLED`
- Entity에는 JPA Auditing으로 `created_at`, `updated_at` 자동 처리 (`JpaConfig`)
- 삭제는 soft delete(`deleted_at`) 원칙 (users)

---

## 7. Redis 키 규칙 (임의 키 추가 금지)

| 키 | TTL | 용도 |
|---|---|---|
| `auth:refresh:{userId}` | 14일 | Refresh Token |
| `auth:blacklist:{accessToken}` | 30분 | 로그아웃 블랙리스트 |
| `auth:email_code:{email}` | 5분 | 이메일 인증코드 |
| `auth:login_fail:{loginId}` | 10분 | 로그인 실패 카운터 (5회 잠금) |
| `stock:price:{stockCode}` | 5초 | 현재가 캐시 |
| `stock:hoga:{stockCode}` | 2초 | 호가 캐시 |
| `pending:orders:{stockCode}` | 없음 | 지정가 미체결 주문 |
| `gemini:rate:{userId}` | 1분/1일 | Gemini Rate Limiter (분당 3회, 일일 10회) |

- Redis 접근은 반드시 `global/redis`의 5개 서비스 클래스를 통해서만 한다.
- 도메인 서비스에서 RedisTemplate 직접 주입 금지.

---

## 8. 아키텍처 필수 준수 사항

- **실시간 시세**: LS증권 WebSocket 수신 → Throttle 200ms → Redis 캐싱 + STOMP 브로드캐스팅 동시 처리
- **STOMP 토픽**: `/topic/stock/{stockCode}` (브로드캐스팅), `/user/{userId}/queue` (유니캐스팅)
- **tick 처리**: `@Async` + 전용 스레드풀 (`AsyncConfig`)
- **지정가 체결**: tick 수신 시 `pending:orders` 확인 → 조건 충족 시 낙관적 락으로 체결
- **서버 시작 순서**: `@PostConstruct`로 DB PENDING 주문 Redis 재적재 완료 후 LS WebSocket 연결
- **LS 재연결**: 지수 백오프 (1→2→4→최대 30초)
- **Gemini 호출 전** 반드시 `RedisRateLimiterService` 통과, 초과 시 429 즉시 반환
- **환경변수/시크릿**: AWS Parameter Store 사용, 코드에 API 키 하드코딩 절대 금지

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

## 변경된 파일
-

## 확인 사항
- [ ] 로컬에서 정상 실행 확인
- [ ] 관련 페이지 이동 확인
- [ ] API 연동 정상 동작 확인
- [ ] 오류 또는 경고 메시지 확인

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
