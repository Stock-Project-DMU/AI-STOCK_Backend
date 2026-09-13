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
| `NotificationType` | `SYSTEM`, `ORDER`, `AI`, `SIMULATION`, `NEWS`(feature/ai-news 추가) | `domain.notification.entity` |
| `InquiryStatus` | `PENDING`, `ANSWERED` | `domain.inquiry.entity` |
| `PriceDirection` | `UP`, `DOWN`, `FLAT` | `domain.stock.dto` (4주차 `feature/stock-price` 추가 — Entity/DB 컬럼이 아니라 `StockPriceResponse` 응답 시점에 `changeRate` 부호로 계산해서 채우는 값이라 `entity`가 아닌 `dto` 패키지에 둔다) |

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
| `Simulation` | `simulationId`, `user`, `stockCode`, `stockName`, `targetAmount`, `investmentAmount`, `targetMonths`, `scenarioData`, `bestReachDate`, `baseReachDate`, `worstReachDate`, `dartData`, `newsData`, `createdAt` |
| `RecentViewed` | `viewId`, `user`, `stockCode`, `stockName`, `viewedAt` |
| `Notification` | `notiId`, `user`, `type`, `title`, `content`, `isRead`, `createdAt` |
| `Inquiry` | `inquiryId`, `user`, `title`, `content`, `status`, `answer`, `answeredBy`, `answeredAt`, `createdAt`, `updatedAt` |
| `NewsBriefingSetting` (feature/ai-news 추가) | `settingId`, `user`, `outletDomain`, `createdAt`, `updatedAt` |
| `NewsBriefing` (feature/ai-news 추가) | `briefingId`, `user`, `outletDomain`, `briefingDate`, `content`, `sourceLinksJson`(요약 근거 기사 JSON, 2026-08-24 추가), `createdAt` |

- **뉴스 브리핑 설정 변경 메서드 (feature/ai-news 추가)**: `NewsBriefingSetting.changeOutlet(String
  outletDomain)` — 언론사 재선택(예: 한국경제 → 매일경제). `NewsBriefing`은 하루치 완성된
  결과라 변경 메서드 없이 생성만 한다.
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
| `UserRepository` | `findByLoginId(String loginId)`, `findByEmail(String email)`, `existsByLoginId(String loginId)`, `existsByEmail(String email)`, `findByUserIdAndIsActiveTrue(Long userId)`, `countByIsActiveTrue()`(관리자 대시보드 — 총 사용자 수), `findAllByIsActiveTrue(Pageable pageable)`(feature/admin-user 코드리뷰 반영 — 관리자 사용자 목록에서 탈퇴 유저 제외, 8-17 참고), `findAllByRoleAndStatusAndIsActiveTrueForUpdate(Role role, UserStatus status)`(feature/admin-user 코드리뷰 반영 — 마지막 남은 ADMIN 정지 방지, 비관적 락으로 동시 정지 요청 경쟁 상태까지 막음, 8-17 참고) |
| `SocialAccountRepository` | `findByProviderAndProviderId(SocialProvider provider, String providerId)`, `deleteByUserId(Long userId)` |
| `InvestmentProfileRepository` | `findByUserId(Long userId)`, `deleteByUserId(Long userId)` |
| `AccountRepository` | `findAllByUserId(Long userId)`(내 계좌 목록, 최대 3건 — `ORDER BY accountId asc` 고정, feature/ai-planning 추가: `AiPlanningService.findPrimaryHolding()`이 "첫 번째 계좌"를 가정하고 `accounts.get(0)`을 쓰는데 ORDER BY가 없으면 호출마다 다른 계좌가 나올 수 있어 생성 순서로 고정), `findAllByUserIdForUpdate(Long userId)`(mypage-account 추가 — `@Lock(PESSIMISTIC_WRITE)`, `AccountService.createAccount()`가 계좌 개수 확인과 저장 사이의 동시 개설 경합을 막는 데 사용. 처음에는 `UserRepository.findByIdForUpdate`로 User 행 전체를 잠갔는데, User는 계좌와 무관한 다른 기능도 앞으로 잠글 수 있는 공용 자원이라 Account 쪽만 잠그는 이 메서드로 좁혔다 — 매칭 행이 0개여도 idx_account_user 인덱스로 갭 락이 걸려 동시 삽입을 막는다), `findByAccountIdAndUserId(Long accountId, Long userId)`(mypage-account 추가 — 계좌 소유권 검증 겸 조회. order-market/order-limit의 `findByUserId(Long userId)`를 대체 — 유저가 계좌를 여러 개 가질 수 있어 단일 계좌를 가정한 조회는 더 이상 쓰지 않는다), `findByAccountIdAndUserIdForUpdate(Long accountId, Long userId)`(mypage-account 추가 — `@Lock(PESSIMISTIC_WRITE)`, `AccountService.chargeBalance()`가 chargeCount 확인과 반영 사이의 동시 충전 경합을 막는 데 사용. `findByAccountIdAndUserId`와 WHERE 절이 동일해 `FIND_BY_ACCOUNT_ID_AND_USER_ID` 상수로 공유), `findByAccountNumber(String accountNumber)`, `findAccountWithUserById(Long accountId)`(feature/admin-account 추가 — `@Query` JOIN FETCH account.user, `AdminAccountService`가 accountId만 갖고 조회할 때 userName을 함께 채우는 데 사용, 8-16 참고), `deleteByUserId(Long userId)` |
| `HoldingRepository` | `findAllByAccountId(Long accountId)`, `findByAccountIdAndStockCode(Long accountId, String stockCode)`, `findFirstByStockCode(String stockCode)`(4주차 `feature/stock-price` 추가 — `StockNameResolver`가 종목명 조회 시 사용, 8-4 참고), `findAllByAccountIdIn(List<Long> accountIds)`(feature/admin-user 코드리뷰 반영 — 계좌별 N+1 조회 대신 배치 조회, 8-17 참고) |
| `OrderRepository` | `findAllByStockCodeAndStatus(String stockCode, OrderStatus status)`, `findAllByAccountIdOrderByOrderedAtDesc(Long accountId)`, `findByOrderIdAndAccountId(Long orderId, Long accountId)`, `findAllByStatusWithAccountAndUser(OrderStatus status)`, `countByStatus(OrderStatus status)`(관리자 대시보드 — 총 거래건수), `findTop20ByStatusOrderByExecutedAtDesc(OrderStatus status)`(관리자 대시보드 — 최근 거래 20건), `findAllOrdersWithUser(Pageable pageable)`(관리자 전체 거래 목록, `@Query` JOIN FETCH account.user), `findOrderWithUserById(Long orderId)`(관리자 거래 상세, `@Query` JOIN FETCH), `sumExecutedAmount()`(관리자 대시보드 — 총 거래대금, `@Query SUM(execPrice*quantity)`), `findByIdForUpdate(Long orderId)`(feature/order-limit 추가 — `@Lock(PESSIMISTIC_WRITE)`, `OrderExecutionService.execute()`용), `findByOrderIdAndUserIdForUpdate(Long orderId, Long userId)`(mypage-account 추가 — `@Lock(PESSIMISTIC_WRITE)`, `OrderService.cancelOrder()`용. 계좌가 여러 개가 되면서 한때 `findByIdForUpdate(orderId)`로 먼저 잠근 뒤 소유자를 나중에 검증하는 방식을 썼는데, 그러면 남의 orderId로도 락이 먼저 걸려버려(락 경합 + 존재 여부를 응답 시간으로 구분당하는 사이드채널) 과거 `findByOrderIdAndAccountIdForUpdate(Long orderId, Long accountId)`처럼 소유권을 WHERE 절(이번엔 accountId 대신 userId로 조인)에 넣어 조회와 동시에 걸러내는 방식으로 되돌렸다), `sumPendingSellQuantity(Long accountId, String stockCode)`(feature/order-limit 추가 — 같은 계좌·종목으로 이미 등록된 PENDING 지정가 매도 주문 수량 합계. `createLimitOrder()`가 매도 등록 시 `보유수량 - 이미 대기 중인 매도 수량`으로 검증해, 같은 종목을 초과해서 중복 매도 등록하는 것을 등록 시점에 막는다. 이 조회는 일반 SELECT라 MySQL 기본 격리수준(REPEATABLE READ)에서는 트랜잭션 시작 시점 스냅샷을 볼 수 있어, `createLimitOrder()` 자체를 `@Transactional(isolation = READ_COMMITTED)`로 지정해 항상 최신 커밋 데이터를 보게 한다), `findFirstByStockCode(String stockCode)`(4주차 `feature/stock-price` 추가 — `StockNameResolver`용, 8-4 참고), `findAllByAccountIdInOrderByOrderedAtDesc(List<Long> accountIds)`(feature/admin-user 코드리뷰 반영 — 계좌별 N+1 조회 대신 배치 조회, `@Query` ORDER BY까지 DB에서 처리, 8-17 참고), `findAllPendingByAccountIdForUpdate(Long accountId)`(feature/admin-account 코드리뷰 반영 — `@Lock(PESSIMISTIC_WRITE)`, `OrderService.cancelAllPendingOrdersForSuspension()`용. 관리자가 계좌를 정지시킬 때 그 계좌의 PENDING 지정가 주문을 일괄 취소하며, `findByIdForUpdate`와 동일한 이유로 `OrderExecutionService.execute()`의 tick 체결과 경합하지 않도록 비관적 락을 건다, 8-16 참고) |
| `WatchlistRepository` | `findAllByUserId(Long userId)`, `existsByUserIdAndStockCode(Long userId, String stockCode)`, `deleteByUserIdAndStockCode(Long userId, String stockCode)`(v14, 4주차 `feature/stock-price`에서 반환 타입 `void`→`int`로 변경 — `WatchlistService.removeWatchlist()`가 실제로 삭제된 행이 있었는지 알아야 `StockSubscriptionManager.decreaseWatchlistSubscription()`을 호출할지 판단할 수 있어서다. `RecentViewedRepository.touchViewedAt()`과 동일한 이유), `deleteByUserId(Long userId)`, `findFirstByStockCode(String stockCode)`(4주차 `feature/stock-price` 추가 — `StockNameResolver`용, 8-4 참고) |
| `AiPlanningSessionRepository` | `findAllByUserIdOrderByUpdatedAtDesc(Long userId)`, `findByUserIdAndSessionId(Long userId, Long sessionId)`, `deleteByUserId(Long userId)` |
| `AiPlanningMessageRepository` | `findAllBySessionIdOrderByCreatedAtAsc(Long sessionId)`, `findRecentBySessionId(Long sessionId, Pageable pageable)`(feature/ai-planning 추가 — 내림차순 + `Pageable`로 최근 N건만 DB 레벨에서 가져온 뒤 `AiPlanningService.buildHistory()`가 다시 뒤집어서 씀. `createdAt`이 초 단위 정밀도라 같은 초에 USER/AI가 저장되는 경우를 대비해 `messageId`를 2차 정렬 키로 사용) |
| `SimulationRepository` | `findAllByUserIdOrderByCreatedAtDesc(Long userId)`, `findByUserIdAndSimulationId(Long userId, Long simulationId)`, `deleteByUserId(Long userId)` |
| `RecentViewedRepository` | `findAllByUserIdOrderByViewedAtDesc(Long userId)`, `findByUserIdAndStockCode(Long userId, String stockCode)`, `touchViewedAt(Long userId, String stockCode)`(mypage-account 추가 — `@Modifying`, 이미 본 종목을 다시 볼 때 새 행 대신 viewedAt만 UPDATE. delete 후 재삽입 방식은 `RecentViewed`가 `@GeneratedValue(IDENTITY)`라 save()가 즉시 INSERT를 실행해버려 아직 flush 안 된 DELETE와 충돌해 `uq_user_stock_view` 위반이 나는 버그가 있어 이 방식으로 교체했다), `deleteByUserId(Long userId)`, `findFirstByStockCode(String stockCode)`(4주차 `feature/stock-price` 추가 — `StockNameResolver`용, 8-4 참고) |
| `NotificationRepository` | `findAllByUserIdOrderByCreatedAtDesc(Long userId)`, `countByUserIdAndIsReadFalse(Long userId)`, `findByNotiIdAndUserId(Long notiId, Long userId)` |
| `NewsBriefingSettingRepository` (feature/ai-news 추가) | `findByUserId(Long userId)`, `findAllWithUser()`(스케줄러가 전체 사용자 순회용 — `@Query` JOIN FETCH user, 트랜잭션 밖에서도 LazyInitializationException 없이 순회하기 위함), `deleteByUserId(Long userId)`(탈퇴 처리용) |
| `NewsBriefingRepository` (feature/ai-news 추가) | `findByUserIdAndBriefingDate(Long userId, LocalDate briefingDate)`, `existsByUserIdAndBriefingDate(Long userId, LocalDate briefingDate)`(스케줄러 중복 생성 방지), `deleteByUserId(Long userId)`(탈퇴 처리용) |
| `InquiryRepository` | `findAllByUserIdOrderByCreatedAtDesc(Long userId)`(사용자 본인 문의 목록), `findByInquiryIdAndUserId(Long inquiryId, Long userId)`(본인 문의 상세, 소유권 검증), `findAllByOrderByStatusDescCreatedAtDesc()`(관리자 전체 목록, 무인자 `List` 버전 — "PENDING"이 "ANSWERED"보다 알파벳순 뒤(P > A)라 status 내림차순 정렬해야 미답변 우선 노출), `findAllByOrderByStatusDescCreatedAtDesc(Pageable pageable)`(같은 정렬 기준의 `Page` 오버로드 — `AdminInquiryService.getInquiries()`용. feature/admin-inquiry 코드리뷰 반영: `@Query` JOIN FETCH user로 N+1 방지, 8-18 참고), `deleteByUserId(Long userId)`(탈퇴 처리용) |

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
| `AI_SESSION_NOT_FOUND` | 404 (feature/ai-planning 추가 — 본인 소유가 아니거나 존재하지 않는 AI 상담 세션 조회/메시지 전송 시) |
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
| `SELF_STATUS_CHANGE_NOT_ALLOWED` | 400 (feature/admin-user 코드리뷰 추가 — 관리자가 `PATCH /api/admin/users/{userId}/status`로 본인 계정을 SUSPENDED로 정지시키려는 경우) |
| `LAST_ADMIN_SUSPEND_NOT_ALLOWED` | 400 (feature/admin-user 코드리뷰 추가 — 활성 상태인 ADMIN이 본인 하나만 남은 상태에서 그 ADMIN을 정지시키려는 경우. 관리자 전원이 `/api/admin/**`에서 잠기는 lockout을 막기 위함) |
| `INVALID_NEWS_OUTLET` | 400 (feature/ai-news 추가 — `PUT /api/ai/news/settings`에 `NewsRelevanceMatcher.OUTLET_NAMES`에 없는 언론사 도메인을 보낸 경우. `AiNewsService.UNRELIABLE_BRIEFING_OUTLET_DOMAINS`(2026-08-24 추가)에 속한 도메인도 동일하게 거부) |
| `NEWS_BRIEFING_NOT_FOUND` | 404 (feature/ai-news 추가 — 아직 스케줄러가 오늘의 브리핑을 만들지 않은 상태에서 `GET /api/ai/news/briefings/today` 조회) |
| `NEWS_SOURCE_DATA_PARSE_ERROR` | 500 (feature/ai-news 추가, 2026-08-24 — `news_briefings.source_links` JSON 컬럼 파싱 실패. `SCENARIO_DATA_PARSE_ERROR`와 동일한 성격, DB 컬럼이라 별도 코드) |
| `PASSWORD_NOT_SET` | 401 (feature/admin-api-p0 추가, 2026-09-07 — 소셜 로그인 전용 계정(`users.password`가 null)이 `POST /api/users/me/password/verify` 또는 `PATCH /api/users/me/password`를 시도하는 경우) |

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
메서드: `preSend(Message<?> message, MessageChannel channel)`, `onSessionDisconnect(SessionDisconnectEvent event)`

> v8 추가: CONNECT 커맨드 검증 통과 시 `RedisOnlineStatusService.addOnline(userId)` 호출.
>
> **DISCONNECT 처리는 `onSessionDisconnect(SessionDisconnectEvent event)` 하나로만 한다 (v9,
> feature/admin-dashboard 코드리뷰 반영 — 최초 구현 때는 `preSend()`의 STOMP DISCONNECT 커맨드
> 분기와 `onSessionDisconnect` 둘 다에서 `removeOnline()`을 불렀다)**: 정상 종료라도 클라이언트가
> DISCONNECT 프레임을 보낸 뒤 소켓이 실제로 닫히면 `SessionDisconnectEvent`도 함께 발행되므로,
> 두 경로 모두 `removeOnline()`을 부르면 세션 하나가 끝났는데 두 번 호출된다. `admin:online:users`가
> 처음엔 Set(SADD/SREM)이라 SREM 중복 호출이 멱등해 무해했는데, 아래 `RedisOnlineStatusService`
> 항목처럼 "유저별 활성 세션 수" Hash로 바뀌면서 중복 호출이 실제 버그가 됐다 — 같은 유저가 탭을
> 여러 개 열어놨을 때 하나만 닫아도 카운트가 2 줄어들어, 나머지 탭이 멀쩡히 연결돼 있는데도
> 온라인 목록에서 빠져버린다. `SessionDisconnectEvent`는 정상/비정상 종료 상관없이 세션 하나당
> 정확히 한 번만 발행되므로, 세션 종료를 세는 지점을 이거 하나로 통일해 해결했다.

### `AsyncConfig`
빈: `tickTaskExecutor()` — 스레드풀 이름 prefix `tick-executor-`

> feature/ai-news 추가: `@EnableScheduling` 어노테이션도 이 클래스에 함께 선언한다(프로젝트
> 최초의 스케줄링 도입이라 별도 `SchedulingConfig`를 새로 만들지 않고, 이미 "실행 관련 설정"을
> 모아두는 이 클래스에 둠). `AiNewsService.generateDailyBriefings()`의 `@Scheduled` 활성화용.

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
| `LsMarketDataListener` (v9 추가) | `onTickReceived(LsTickData tickData)`, `onHogaReceived(LsHogaData hogaData)` — infra는 domain을 직접 참조하지 않으므로(CLAUDE.md 4번), `LsWebSocketHandler`가 파싱한 시세를 domain에 넘기기 위한 콜백 인터페이스. 4주차 `feature/stock-price`의 `StockBroadcastService`가 이를 구현해 스프링 빈으로 등록하면 자동으로 연결된다. |
| `LsTickData` (dto) | `stockCode`, `stockName`, `currentPrice`, `changeRate`, `changeAmount`(v14 추가), `volume`, `tradedAt` — `stockName`은 LS 실시간 체결 응답에 종목명 필드 자체가 없어 파싱 시 항상 `null`로 둔다(아래 참고). `changeAmount`는 LS 원본 `change` 필드(전일대비, 항상 부호 없는 절대값)를 그대로 옮긴 것 — 부호 없음에 주의, 부호를 반영한 최종 등락 금액은 4주차 `feature/stock-price`의 `StockBroadcastService`가 `changeRate` 부호를 적용해 `StockPriceDto.changeAmount`로 변환할 때 붙인다 |
| `LsHogaData` (dto) | `stockCode`, `askPrices`(List), `askVolumes`(List), `bidPrices`(List), `bidVolumes`(List) |
| `LsTokenResponse` (dto, v10 추가) | `accessToken`, `tokenType`, `expiresIn`, `scope` — `/oauth2/token` 응답(`access_token`/`token_type`/`expires_in`/`scope`)을 Jackson `@JsonProperty`로 매핑하는 내부 DTO. `LsWebSocketClient`가 토큰 발급 시에만 사용 |
| `LsAuthenticationException` (v10 추가) | `LsWebSocketClient`가 `/oauth2/token` 발급 응답을 HTTP 401/403으로 받았을 때 던지는 런타임 예외. `LsReconnectService.reconnectWithBackoff()`가 이 예외 타입으로 "인증 실패로 의심되는 경우"와 "네트워크 문제로 의심되는 경우"를 구분해 로그를 남긴다 (도메인에 노출되는 예외가 아니라 infra 내부 재연결 로직 전용이라 `CustomException`/`ErrorCode`를 쓰지 않음) |

**연동 정보 (LS증권 Open API 공식 가이드 확인, v9)**
- 실시간시세 WebSocket: 실서버 `wss://openapi.ls-sec.co.kr:9443/websocket`, 모의투자 `wss://openapi.ls-sec.co.kr:29443/websocket`
- 접근토큰 발급: `POST https://openapi.ls-sec.co.kr:8080/oauth2/token` (`application/x-www-form-urlencoded`, `grant_type=client_credentials&appkey=...&appsecretkey=...&scope=oob`) — 실서버/모의투자 공통 엔드포인트이며 appkey/appsecret 자체가 계정을 구분
- 실시간 등록 메시지: `{"header":{"token":"...","tr_type":"3"},"body":{"tr_cd":"...","tr_key":"종목코드"}}` (해제는 `tr_type":"4"`)
- 체결 TR코드: 코스피 `S3_`, 코스닥 `K3_` (종목코드만으로는 시장을 구분할 수 없고 `stocks` 마스터 테이블도 없어(schema.sql 13개 테이블 고정), `subscribe()`/`unsubscribe()`는 두 TR코드 모두에 동일한 tr_key로 등록·해제한다 — 해당 종목이 속하지 않는 시장의 TR은 데이터가 오지 않을 뿐 부작용 없음)
- 호가 TR코드 (v10 확정): 코스피 `H1_`, 코스닥 `HA_` — 체결과 동일하게 두 TR코드 모두 동일한 tr_key로 등록·해제
- 체결(`S3_`/`K3_`) 응답 body 실제 필드명 (**v13 장중 실측 확정** — 2026-07-28 09:16~09:19 KST, 삼성전자 등 12개 종목으로 라이브 캡처): `shcode`(종목코드), `price`(현재가), `change`(전일대비, **항상 부호 없는 절대값 문자열** — 하락 종목에서도 음수로 오지 않음, 실측으로 음수 case 0건 확인), `drate`(등락률%, **이미 부호가 포함된 숫자 문자열**로 온다 — 하락 시 `"-8.17"`처럼 `-`가 붙어서 오므로 `asDouble()`로 그대로 파싱하면 부호까지 정확함, 실측 확정), `sign`(등락구분 코드 — 실측으로 `2`=상승 `3`=보합 `5`=하락 확인됨, KRX 상하한가를 친 종목이 캡처 시간대에 없어 `1`=상한 `4`=하한은 **미관측**·통상적 관례상 추정치일 뿐 아직 실측 못함), `cgubun`(**주의: NAMING.md v10에서 이 필드가 등락구분 코드를 담는다고 가정했던 것은 틀렸다** — 실제로는 `+`/`-` 두 값만 오고, 같은 종목·같은 `sign`·같은 `drate` 부호를 유지한 채로도 체결마다 `+`/`-`가 계속 바뀌는 것이 실측으로 확인됨(예: KB금융 105560이 `sign:"2"`/`drate:"0.06"`으로 상승 유지 중에도 연속 체결에서 `cgubun`이 `+`→`-`로 바뀜). 즉 일별 등락 방향과 무관한 필드로 보이며(직전 체결 대비 매수/매도 체결구분 등으로 추정되나 공식 문서로 확인 전까지 의미 미확정), **일별 등락 방향 판단에는 쓰면 안 된다**), `volume`(누적거래량), `chetime`(체결시각 HHMMSS), `open`/`high`/`low`(시가/고가/저가). 이 외에도 `mdchecnt`/`mschecnt`/`mdvolume`/`msvolume`/`w_avrg`/`cpower`/`offerho`/`bidho`/`cvolume`/`value`/`opentime`/`hightime`/`lowtime`/`jnilvolume`/`exchname`/`status` 필드가 함께 오나 `LsTickData`가 쓰지 않아 무시한다(파싱 안 해도 무방, 실측 확인). **응답에 종목명 필드가 없다** — `LsTickData.stockName`은 파싱 시 `null`로 두고, 종목명이 필요한 화면은 4주차 `StockBroadcastService` 쪽에서 별도로 채운다 (v10, 사용자 확인).
- 호가(`H1_`/`HA_`) 응답 body 실제 필드명 (v10 확정, **v13 장중 실측으로 재확인**): `shcode`(종목코드), `offerho1`~`offerho10`(매도호가 1~10단계), `offerrem1`~`offerrem10`(매도잔량 1~10단계), `bidho1`~`bidho10`(매수호가 1~10단계), `bidrem1`~`bidrem10`(매수잔량 1~10단계), `hotime`(호가시각 HHMMSS) — `askPrices`/`askVolumes`/`bidPrices`/`bidVolumes`는 인덱스 0~9가 각각 1~10단계에 대응. 이 외에도 `totofferrem`/`totbidrem`/`midprice`/`midsumrem`/`midsumremgubun`/`offermidsumrem`/`bidmidsumrem`/`donsigubun`/`alloc_gubun`/`volume` 필드가 함께 오나 `LsHogaData`가 쓰지 않아 무시한다(실측 확인).
- 환경변수: `LS_TOKEN_URL`(기본값 있음), `LS_APP_KEY`, `LS_APP_SECRET`(기본값 없음, 반드시 로컬 환경변수로 주입), `LS_WEBSOCKET_URL`(dev는 모의투자 기본값, prod는 필수)
- **토큰 발급 실패 응답 포맷 (v11 실측 확정)**: 잘못된 appsecret으로 `/oauth2/token` 요청 시 `HTTP 403`, body `{"error_code":"IGW00105","error_description":"유효하지 않은 AppSecret입니다."}` — `LsWebSocketClient.issueAccessToken()`이 `HttpClientErrorException.Unauthorized`/`.Forbidden`(401/403)을 잡아 `LsAuthenticationException`으로 변환하는 기존 구현이 실측으로 확인됨. `LsReconnectService`의 "인증 실패 의심"/"네트워크 문제 의심" 로그 분기는 확정 상태로 유지.
- **실시간 등록/해제 ACK 응답 포맷 (v12 실측 확정)**: 등록(`tr_type:"3"`) 요청에 대한 응답은 body 없이 `{"header":{"tr_type":"3","tr_cd":"S3_","rsp_cd":"...","rsp_msg":"..."},"body":null}` 형태로 온다 — 성공 시 `rsp_cd:"00000"`, `rsp_msg:"정상처리되었습니다"` (모의투자 계정으로 재확인, v12), 실패 시(예: 계좌 성격/접속 서버 불일치) `rsp_cd:"10001"`과 원인 메시지(v11). 실제 체결/호가 데이터는 이 ACK과 별개로 이후 도착하는, body가 채워진 메시지로 온다. `LsWebSocketHandler.handleMessage()`는 `header.rsp_cd`가 있으면(성공/실패 무관) body를 파싱하지 않고 무시하되, `00000`은 debug 로그, 그 외는 warn 로그로 구분한다 (v12).
- **모의투자 앱키 정정 후 재확인 (v12)**: `.env`를 모의투자용 appkey/appsecret으로 교체 후 재테스트한 결과, 토큰 발급 성공(HTTP 200) + S3_/K3_/H1_/HA_ 4개 TR 모두 등록 ACK `rsp_cd:"00000"` 정상 수신 확인. 다만 테스트 시점이 장 마감 이후(23시경 KST)라 실제 체결/호가 tick 이벤트 자체가 발생하지 않아, 체결/호가 응답 body의 실제 필드명은 **아직 라이브로 재검증되지 못했다** — 위 필드명은 여전히 공식 카탈로그 문서 예시(v10) 기준. 장중(평일 09:00~15:30 KST)에 재접속해서 실제 body를 받아 최종 검증 필요.
- **부호 처리 최종 확정 (v13, 2026-07-28 09:16~09:19 KST 장중 실측)**: 삼성전자(005930, 하락), SK하이닉스(000660, 하락), KB금융(105560, 상승→하락 전환 포함) 등 12개 종목 실시간 체결 데이터로 검증 완료. 결론 — `drate`는 API가 이미 부호를 포함해서 보내주므로(`"-8.17"` 등) `LsWebSocketHandler.parseTick()`이 `body.path("drate").asDouble()`로 그대로 파싱하는 기존 구현이 **추가 보정 없이 정확함**을 확인했다(코드 변경 불필요). `change`는 반대로 항상 부호 없는 절대값이라 방향 판단에 못 쓰지만 `LsTickData`가애초에 `change`를 읽지 않으므로 영향 없음. `cgubun`은 등락구분이 아니라 체결마다 바뀌는 별도 성격의 필드로 확인돼(위 필드명 항목 참고) 애초에 방향 판단용으로 쓰면 안 되고, 실제 등락구분 코드는 `sign`(2상승/3보합/5하락 실측 확인, 1상한/4하한 미관측)에 있다. 현재 `LsTickData`/`parseTick()`은 `sign`/`cgubun`을 아예 안 읽으므로 기존 구현 그대로 유지.
- **`changeAmount` 파싱 추가 (v14, 4주차 `feature/stock-price`)**: `StockPriceDto.changeAmount`(등락 금액, 원)를 채우려면 `changeRate`만으로는 부족해(반올림 오차 발생) LS 원본 `change` 필드(전일대비 절대값, 부호 없음)를 새로 파싱하기로 확정했다. `LsTickData`에 `changeAmount` 필드를 추가하고 `parseTick()`에 `.changeAmount(body.path("change").asLong())`를 추가한다 — `drate`/`sign`/`cgubun` 처리는 v13 결론 그대로 유지, `change` 필드 파싱만 새로 켠다.

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
| `RedisAiToolCacheService` (feature/ai-planning 추가) | `getCachedResult(Long sessionId, String toolKey)`, `cacheResult(Long sessionId, String toolKey, String result)` — 키 `ai:tool:{sessionId}:{toolKey}`(TTL 30분), AI 상담 세션 내 DART/LS/네이버 도구 실행 결과 캐시(같은 조건 재조회 시 재사용). 8-9 참고 |

> **`admin:online:users`를 Set → Hash로 변경 (v9, feature/admin-dashboard 코드리뷰 반영)**:
> 원래 Set(SADD/SREM, 값=userId)이었는데, 같은 유저가 탭을 여러 개 열어 세션이 여러 개 생긴
> 상태에서 그중 하나만 닫혀도 `removeOnline()`(SREM)이 그 유저를 통째로 지워버려, 나머지 탭이
> 여전히 연결돼 있는데도 관리자 화면엔 즉시 오프라인으로 보이는 문제가 있었다. Set이 막아주는
> 건 "같은 유저를 여러 번 세는 중복 카운트" 문제뿐이지 "세션 중 하나만 끊겨도 전체가 꺼지는"
> 문제는 막지 못한다. 그래서 `admin:online:users`를 Hash(field=userId, value=그 유저의 활성
> WebSocket 세션 수)로 바꿔, `addOnline()`은 HINCRBY(+1), `removeOnline()`은 HINCRBY(-1) 후
> 결과가 0 이하면 그 필드를 HDEL하는 방식으로 세션 수를 센다. 감소+정리를 자바 쪽에서
> "감소 후 조회해서 0이면 삭제"로 따로 하면 그 사이 다른 세션의 CONNECT(증가)가 끼어들 때
> 방금 새로 생긴 세션까지 같이 지워버릴 수 있어, Lua 스크립트로 원자적으로 묶었다.
> `countOnline()`은 HLEN(활성 세션이 1개 이상인 유저 수), `isOnline()`은 HEXISTS로 바뀌었고
> 둘 다 여전히 O(1)이라 Set일 때의 성능 특성은 그대로 유지된다. 이 변경은 `removeOnline()`이
> 세션 하나당 정확히 한 번만 호출된다는 전제가 필요해, `StompAuthInterceptor` 쪽도 함께
> 정리했다(위 `StompAuthInterceptor` 항목 참고).

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
| Broadcast | `StockBroadcastService` — `onTickReceived(LsTickData tickData)`, `broadcastPrice(String stockCode, StockPriceDto dto)`, `onHogaReceived(LsHogaData hogaData)`, `broadcastHoga(String stockCode, HogaDto dto)` (`LsMarketDataListener`의 두 콜백을 모두 구현) |
| 종목명 조회 | `StockNameResolver`(domain/stock/service) — `resolveStockName(String stockCode)`. `LsTickData.stockName`이 항상 null로 오기 때문에, `WatchlistRepository`/`HoldingRepository`/`OrderRepository`/`RecentViewedRepository`의 `findFirstByStockCode(String stockCode)`를 순서대로 조회해 처음 찾은 이름을 반환한다(admin 도메인과 동일하게 여러 도메인의 Repository를 직접 주입받아 조합하는 예외적 컴포넌트, CLAUDE.md 4번 참고). 4개 테이블 어디에도 없으면 null을 반환하고, `StockBroadcastService`는 이 경우 `stockCode`를 이름 대신 사용한다 — 근본 해결은 하단 `> 종목명 폴백 한계` 참고 |
| Response DTO | `StockPriceResponse`(stockCode, stockName, currentPrice, changeAmount, changeRate, direction, volume), `HogaResponse`(stockCode, askPrices, askVolumes, bidPrices, bidVolumes) — `direction`은 `PriceDirection`(0번 전역 Enum 참고), `changeAmount`는 `StockBroadcastService.toStockPriceDto()`가 계산한 부호 있는 등락 금액을 그대로 응답에 노출한 것(v14) |
| STOMP 토픽 (v14 확정) | 가격 `/topic/stock/{stockCode}`(`StockPriceResponse`), 호가 `/topic/stock/{stockCode}/hoga`(`HogaResponse`) — REST 엔드포인트 구분(`GET /api/stocks/{stockCode}` vs `/hoga`)과 동일하게 분리, 프론트가 가격만 필요하면 호가 토픽은 구독하지 않아도 됨 |
| 구독 관리 (v14 확정) | `StockSubscriptionManager`(domain/stock/service) — `increaseWatchlistSubscription(String stockCode)`, `decreaseWatchlistSubscription(String stockCode)`, `increaseViewingSubscription(String stockCode)`, `decreaseViewingSubscription(String stockCode)`. `LsWebSocketClient`는 `ls.mode=real`일 때만 빈으로 존재하므로 `Optional<LsWebSocketClient>`로 생성자 주입받는다 — mock 모드(`Optional.empty()`)에서는 구독 카운터만 갱신하고 실제 `subscribe()`/`unsubscribe()` 호출은 debug 로그만 남기고 건너뛴다 |
| STOMP 세션 리스너 (v14 확정) | `StockViewSubscriptionListener`(global/stomp) — `handleSessionSubscribe(SessionSubscribeEvent event)`, `handleSessionUnsubscribe(SessionUnsubscribeEvent event)`, `handleSessionDisconnect(SessionDisconnectEvent event)` |

> **종목명 폴백 한계 (v14 추가, 미해결 이슈)**: `StockNameResolver`는 우리 DB(watchlist/holdings/
> orders/recent_viewed)에 이미 등록된 종목만 이름을 찾을 수 있다. 아무도 관심등록·매매·조회한
> 적 없는 신규/미거래 종목은 LS tick도 `stockName`이 null로 오고 우리 DB 어디에도 이름이 없어
> `stockCode`가 그대로 노출된다. 이 브랜치 범위에서는 해결하지 않고 `KNOWN_ISSUES.md`에 별도
> 이슈로 남긴다 — 근본 해결책(DART 기업개황 API 연동 vs `stock_master` 테이블 신설)은 팀 회의에서
> 결정 필요.
>
> **구독(subscribe) 트리거 시점 확정 (v14)**: 하이브리드 방식으로 간다.
> 1. `WatchlistService.addWatchlist()`가 실제로 새 행을 저장했을 때(멱등 스킵/동시성 충돌로
>    실패한 경우는 제외) `StockSubscriptionManager.increaseWatchlistSubscription()`을 호출해
>    영구 구독으로 취급한다. `removeWatchlist()`가 실제로 행을 삭제했을 때(`deleteByUserIdAndStockCode`
>    반환값 > 0, 위 1-2 참고) `decreaseWatchlistSubscription()`을 호출한다.
> 2. 종목 상세페이지 진입 시 클라이언트가 `/topic/stock/{stockCode}`를 STOMP SUBSCRIBE하면
>    `StockViewSubscriptionListener.handleSessionSubscribe()`가 destination에서 stockCode를
>    추출해 `increaseViewingSubscription()`을 호출하고 (세션ID, 구독ID) → stockCode 매핑을
>    저장한다. 클라이언트가 UNSUBSCRIBE하면 `handleSessionUnsubscribe()`가 매핑을 조회해
>    `decreaseViewingSubscription()`을 호출한다. 세션이 비정상 종료(UNSUBSCRIBE 프레임 없이
>    끊김)되면 `handleSessionDisconnect()`가 그 세션이 갖고 있던 매핑 전부에 대해
>    `decreaseViewingSubscription()`을 호출하고 정리한다 — `SessionDisconnectEvent`로 보완
>    처리하는 패턴은 CLAUDE.md 8번의 온라인 추적 정책과 동일한 논리다.
> 3. `StockSubscriptionManager`는 종목별 구독자 수(관심종목+조회 합산)를
>    `ConcurrentHashMap<String, AtomicInteger>`로 관리한다(단일 서버 운영 전제 — Redis 키로
>    승격할지는 다중 서버로 확장될 때 재검토). 0→1 전환 시 `LsWebSocketClient.subscribe()`,
>    1→0 전환 시 `unsubscribe()`를 호출한다.
> 4. 소켓당 종목 512개 제한: 0→1 전환으로 새 종목을 구독하려는 시점에 이미 관리 중인 종목 수가
>    512개 이상이면 `subscribe()` 호출 자체는 그대로 진행하되 warn 로그만 남긴다(실제 초과 여부·
>    거부 응답은 `LsWebSocketHandler`의 기존 ACK 로깅으로 확인 — 본격적인 대응은 범위 밖).
>
> **(v14 발견, feature/admin-dashboard에서 해소)**: 이 브랜치(feature/stock-price) 시점에는
> CLAUDE.md 8번/NAMING.md 7번이 기술한 `RedisOnlineStatusService`/`StompAuthInterceptor`의
> CONNECT/DISCONNECT 온라인 추적이 실제로는 구현되어 있지 않았다(`StompAuthInterceptor`는 CONNECT
> 인증만 처리, `SessionDisconnectEvent` 리스너도 저장소에 없었음). `StockViewSubscriptionListener`가
> 그 시점 저장소 최초의 STOMP 세션 이벤트 리스너였다. 이 불일치는 `KNOWN_ISSUES.md` 2번에 남겨뒀다가
> `feature/admin-dashboard`가 온라인 사용자 수 집계를 실제로 필요로 하면서 `RedisOnlineStatusService`
> 구현 + `StompAuthInterceptor.onSessionDisconnect(SessionDisconnectEvent)` 추가로 해소했다
> (8-14 참고). `KNOWN_ISSUES.md` 2번도 그때 함께 제거했다.

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
| Service (OrderService 추가) | `createLimitOrder(Long userId, CreateOrderRequest request)`, `cancelOrder(Long userId, Long orderId)`, `cancelAllPendingOrdersForSuspension(Account account)`(feature/admin-account 코드리뷰 반영, v8 — `AdminAccountService.updateAccountStatus()`가 계좌를 SUSPENDED로 바꿀 때 함께 호출. `cancelOrder()`와 달리 소유권 검증·SUSPENDED 차단을 하지 않는다(정지 처리 자체의 일부이므로). `OrderRepository.findAllPendingByAccountIdForUpdate(accountId)`로 그 계좌의 PENDING 지정가 주문 전부를 비관적 락으로 조회해, 매수 주문이면 `account.unfreezeForOrder()`로 동결 해제 후 `order.cancel()`, 커밋 후 Redis `pending:orders`에서도 제거한다 — 정지 후에도 tick 체결이 계속되거나 사용자가 취소도 못 하는 상태로 남는 것을 막는다, 8-16 참고) |
| Execution Service | `OrderExecutionService` — `execute(PendingOrderDto pendingOrder, long currentPrice)`, `checkAndExecute(String stockCode, long currentPrice)` |
| Response DTO | `OrderHistoryResponse`(accountId, orderId, stockCode, stockName, orderType, priceType, orderPrice, execPrice, quantity, status, orderedAt, executedAt) |
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
| Response DTO | `ProfitResponse`(totalAsset, profitAmount, profitRate), `HoldingResponse`(accountId, stockCode, stockName, quantity, avgPrice, currentPrice, evaluationProfit), `UserInfoResponse`(userId, loginId, name, email, role, `status`), `InvestmentProfileResponse`(investmentTendency, fundTendency, investmentLevel) |
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
> **`HoldingValuationDto`(코드리뷰 반영, `domain.order.dto`)**: `accountId, stockCode, stockName,
> quantity, avgPrice, currentPrice` 필드를 갖는 레코드. 처음엔 이름이 `HoldingValuation`(Dto 접미사
> 없음)이었고 `Holding` 엔티티를 통째로 담고 있었는데, 코드리뷰에서 두 가지가 지적됐다 —
> ① CLAUDE.md 5번 규칙의 "내부 DTO는 XxxDto" 이름 규칙 위반, ② account 도메인이
> `HoldingValuationService`를 거치고도 여전히 `Holding` 엔티티의 메서드(`getQuantity()` 등)를
> 직접 호출해 도메인 경계를 넘는 목적이 절반만 달성됨. 정적 팩토리 `HoldingValuationDto.
> of(Holding holding, Long currentPrice)`로 엔티티에서 필요한 값만 꺼내 담도록 정리했고,
> `HoldingResponse.of(Holding, long)`도 `HoldingResponse.of(HoldingValuationDto)`로 바꿔
> 같은 라운드에 추가된 두 DTO가 동일하게 정적 팩토리 메서드를 쓰도록 맞췄다.
>
> **`accountId` 필드 추가 (feature/admin-user 코드리뷰 반영, v8)**: `AdminUserDetailResponse`가
> 유저의 여러 계좌(최대 3개) holdings/orders를 하나의 flat list로 합치면서, 같은 종목을
> 계좌 A/B에 각각 보유 중이면 관리자 화면에서 어느 계좌 소속인지 구분할 수 없는 문제가 있었다.
> `HoldingValuationDto`/`HoldingResponse`/`OrderHistoryResponse`에 `accountId`를 추가해
> (각각 `holding.getAccount().getAccountId()` / `order.getAccount().getAccountId()`에서 꺼냄)
> 해결했다. 이 DTO들은 마이페이지(`GET /api/accounts/{accountId}/...`, `feature/mypage-*`)에서도
> 재사용되는데, 마이페이지 쪽은 이미 accountId를 알고 있는 컨텍스트라 새 필드가 있어도 무해하다.
> `HoldingValuationService`의 두 오버로드(`getHoldingValuations(Long)` / `getHoldingValuations(List<Long>)`)가
> 갖고 있던 "시세 배치 조회 + 캐시 미스 시 평단가 폴백 매핑" 중복 로직도 이 라운드에 `valuate()`
> private 메서드로 합쳤다(공개 시그니처·distinct() 여부 차이는 그대로 유지).
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

**Tavily는 뉴스 검색 백엔드로 쓰다가 2026-08-05 네이버로 교체, 2026-08-06 `TavilyApiClient`/dto 완전
삭제됨(CLAUDE.md 참고). 아래는 LS증권 Open API 대대적 확장(2026-08-10~11) 이후 최종 상태.**

| 구분 | 이름 |
|---|---|
| Controller | `AiPlanningController` |
| 엔드포인트 | `POST /api/ai/planning/sessions`, `GET /api/ai/planning/sessions`, `GET /api/ai/planning/sessions/{sessionId}/messages`, `POST /api/ai/planning/sessions/{sessionId}/messages` — 사용자 식별은 4개 전부 요청 바디가 아니라 `SecurityUtil.getCurrentUserId()`로 한다 |
| Service | `AiPlanningService` — `createSession(Long userId)`, `getMySessions(Long userId)`, `getMessages(Long userId, Long sessionId)`, `sendMessage(Long userId, Long sessionId, AiChatRequest request)`. `loadHistory(Long userId, Long sessionId)`/`saveTurn(Long userId, Long sessionId, String userContent, GeminiResponse geminiResponse)`도 public인데, 실제 용도가 아니라 `@Lazy self` 프록시로 자기 자신을 주입받아 `@Transactional` 트랜잭션 경계를 강제로 나누기 위한 self-invocation 패턴(Spring AOP 프록시는 같은 빈 안에서의 직접 호출엔 안 걸리는 한계 우회) — 외부에서 호출할 일은 없다 |
| Request DTO | `AiChatRequest`(`@NotBlank @Size(max = 2000) content`) — 2000자 제한은 DB TEXT 컬럼 여유 확보 + Gemini 토큰 비용 통제 목적 |
| Response DTO | `AiChatResponse`(messageId, role, content, createdAt), `AiPlanningSessionResponse`(sessionId, title, status, createdAt, updatedAt) — 둘 다 정적 팩토리 `from(Entity)` 보유 |

**Gemini 연동 (`infra/gemini`)**
- `GeminiApiClient.generate(GeminiRequest request)` — 단일 메서드. `judgeApiUrl`(`app.gemini.judge-api-url`)/
  `answerApiUrl`(`app.gemini.answer-api-url`) 두 모델 URL을 `request.model()`(`GeminiRequest.GeminiModel`
  — `JUDGE`/`ANSWER`)로 분기해서 호출한다. `JUDGE`는 도구 호출 여부/어떤 도구를 쓸지 판단하는 라운드
  겸 도구가 필요 없는 잡담·되묻기의 최종 답변까지 담당하는 저렴한 모델(현재 둘 다
  `gemini-3.1-flash-lite`로 수렴 — 애초 계획한 2.5-flash-lite/2.5-flash 조합은 이 계정에서 404라 못 씀,
  `-latest` 별칭은 더 비싼 3.6-flash로 뜨는 문제도 있어 회피), `ANSWER`는 도구 결과·보유종목·투자성향을
  종합하는 최종 강제-텍스트 라운드(이 라운드엔 `tools`를 아예 안 보낸다) 전용.
- `GeminiRequest`(systemInstruction, prompt, history, tools, functionExchangeRounds, model) — `systemInstruction`은
  `contents`/`prompt`와 완전히 분리된 별도 최상위 JSON 필드로 매 턴 한 번만 전송한다(2026-08-07 확정,
  과거엔 매 턴 `contents`에 통째로 재삽입해서 모델이 "방금 지침을 받은 것"처럼 여겨 인사를 계속
  반복하는 버그가 있었음). 내부 record: `GeminiModel`(enum `JUDGE`/`ANSWER`), `HistoryTurn`(role, content),
  `ToolDeclaration`(name, description, parameters, required), `ParameterSpec`(type, description),
  `FunctionExchange`(functionName, args, thoughtSignature, functionResult)
- `GeminiResponse`(content, tokenCount, functionCalls) — `isFunctionCall()`, 내부 record `FunctionCall`(name, args, thoughtSignature)

**DART 연동 (`infra/dart`)**
- `DartApiClient` — `getFinancials(DartFinancialRequest request)`, `getRecentQuarterlyFinancials(String corpCode)`,
  `getCapitalChangeDecisions(String corpCode, String changeType)`, `getOwnershipInfo(String corpCode, String infoType)`,
  `getSupportedDisclosureTypesWithDescription()`(static — 공시유형 약 70종의 이름을 `DISCLOSURE_REGISTRY`에서
  동적으로 뽑아 Gemini 도구 스키마 enum에 그대로 꽂는다, NAMING.md에 70종을 전부 나열하지 않고 코드
  `DartApiClient.DISCLOSURE_REGISTRY`를 원본으로 삼는다), `getDisclosureInfo(String corpCode, String disclosureType)`,
  `isBlankOfContent(List<Map<String, Object>> items, Set<String> boilerplateKeys)`, `resolveCorpCodeByName(String companyName)`,
  `resolveStockCodeByName(String companyName)` — 2026-08-13 두 메서드 모두 정확 일치 실패 시 2단계
  폴백 추가: (1) 대소문자·법인 접미사("주식회사"/"(주)"/"㈜") 무시한 정규화 일치, (2) 그래도 실패하면
  `GROUP_NAME_ALIASES`(SK↔에스케이/LG↔엘지/GS↔지에스/CJ↔씨제이/KT↔케이티, 보수적으로 확인된 것만
  등록)로 그룹명을 치환해 재시도. 라이브 테스트로 "sk쉴더스"가 DART 원본엔 "에스케이쉴더스"로만
  등록돼 있어 정확 일치로 못 찾던 문제 실측 후 추가(뉴스 검색의 `NewsRelevanceMatcher.KNOWN_ALIASES`와
  같은 원칙)
- `DartFinancialRequest`(corpCode, year), `DartFinancialResponse`(corpCode, bizYear, revenue, operatingProfit,
  netIncome, totalAssets, totalLiabilities, totalEquity)

**AI 도구(Gemini function-calling) 27개** — `AiPlanningService.converseWithTools()`가 매 라운드 전달하는
`tools` 목록. `*_TOOL_NAME` 상수 → 파라미터(괄호 안은 enum 값) → 실제 처리 클라이언트/TR 순.
"묶음형"은 하나의 도구가 mode류 파라미터로 여러 LS TR을 분기해서 부르는 도구(48개 후보를 18개로
압축한 결과, CLAUDE.md 4번 "infra는 domain을 통해서만" 원칙과 별개로 이건 Gemini 판단 정확도·
프롬프트 비용 문제로 압축한 것).

| 도구 이름 | 파라미터 | 분기(묶음형만) → 실제 호출 |
|---|---|---|
| `search_securities_news` | companyName(필수), topic, periodDays | `NaverNewsApiClient.search()` |
| `get_financial_statements` | companyName(필수), period(`연간`/`분기`) | `연간`→`DartApiClient.getFinancials()`, `분기`→`getRecentQuarterlyFinancials()` |
| `get_capital_change_info` | companyName(필수), changeType(`유상증자`/`무상증자`) | `DartApiClient.getCapitalChangeDecisions()` |
| `get_ownership_info` | companyName(필수), infoType(`현황`/`변동`) | `DartApiClient.getOwnershipInfo()` |
| `get_disclosure_info` | companyName(필수), disclosureType(`DISCLOSURE_REGISTRY` 약 70종) | `DartApiClient.getDisclosureInfo()` |
| `get_current_price` | companyName(필수) | `LsMarketDataApiClient.getCurrentPrice()`(t1102) — 30분 도구 캐시 대상에서 제외, `ConfirmedPrice` 확정 패턴 적용(아래 참고) |
| `get_foreign_institutional_trend` | companyName(필수), periodMonths(선택, 1~24) | `LsInvestorTrendApiClient.getTrend()`(t1716, periodMonths 있으면 최대 2년까지 조회 후 합계·최고/최저일 요약) |
| `get_investment_opinion` | companyName(필수) | `LsInvestInfoApiClient.getInvestmentOpinions()`(t3401) |
| `get_shareholder_meeting_schedule` | companyName(필수) | `LsInvestInfoApiClient.getShareholderMeetingSchedule()`(t3202) |
| `get_market_ranking` | rankingType(`PRICE_CHANGE_RATE`/`MARKET_CAP`/`VOLUME`/`TRADING_VALUE`/`VOLUME_SURGE`/`AFTER_HOURS_PRICE_CHANGE_RATE`/`AFTER_HOURS_VOLUME`) | `LsHighItemApiClient`의 7개 메서드(t1441/t1444/t1452/t1463/t1466/t1481/t1482)로 1:1 분기. `AFTER_HOURS_*` 2개는 `DateUtil.isAfterHoursTradingTime()` 게이트 |
| `get_theme_info` | mode(`THEME_TO_STOCKS`/`STOCK_TO_THEMES`/`HOT_THEMES`), themeName(조건부), companyName(조건부) | `LsSectorApiClient`의 `getThemeConstituentsByName()`(t8425 이름→코드 캐시 후 t1537)/`getThemesForStock()`(t1532)/`getHotThemes()`(t1533) |
| `get_financial_ranking` | criteria(`SALES_GROWTH`/`OPERATING_INCOME_GROWTH`/`DEBT_RATIO`/`EPS`/`BPS`/`ROE`/`PER`/`PBR`/`PEG`) | `LsInvestInfoApiClient.getFinancialRanking()`(t3341) — criteria는 코드(`1`/`2`/`4`/`6`/`7`/`8`/`9`/`a`/`b`)로 매핑 후 전달 |
| `get_overseas_index` | indexName(`다우지수`/`나스닥`/`원달러환율`/`국제유가`) | `LsInvestInfoApiClient.getOverseasIndex()`(t3521) — 종목 심볼로 매핑 후 전달 |
| `get_market_liquidity_trend` | periodMonths(선택, 1~24) | `LsInvestInfoApiClient.getMarketLiquidityTrend()`(t8428, periodMonths 있으면 최대 2년까지 조회 후 최고/최저일 요약) |
| `get_stock_technical_signal` | companyName(필수) | `LsMarketDataApiClient.getPivotLevels()`(t1105) |
| `get_historical_price` | companyName(필수), periodMonths(선택, 1~24) | `LsMarketDataApiClient.getHistoricalPrices()`(t1305, periodMonths 있으면 월봉으로 전환해 최대 24개월 조회 — open/high/low 실측값으로 기간 내 최고가·최저가를 코드가 직접 계산해 답에 덧붙임) |
| `get_multi_stock_price` | companyNames(필수, 콤마구분 최대 5개) | `LsMarketDataApiClient.getMultiStockPrices()`(t8407) — `get_current_price`와 동일하게 30분 도구 캐시 대상에서 제외, `ConfirmedPrice` 확정 패턴 적용(아래 참고). 최초 구현 시 캐시 제외 분기에서 누락돼 캐시 히트 시 `ConfirmedPrice`가 안 쌓이던 버그가 있었음(코드리뷰로 발견, `executeTool()` 수정으로 해결) |
| `get_stock_risk_flag` | companyName(필수) | `LsMarketDataApiClient.getRiskFlags()`(t1404 관리종목 + t1405 투자경고/매매정지) |
| `get_call_auction_price` | companyName(필수) | `LsMarketDataApiClient.getRecentCallAuctionPrices()`(t1486) — `DateUtil.isCallAuctionTime()` 게이트, `get_current_price`와 동일하게 30분 도구 캐시 대상에서 제외(동시호가 예상체결가는 그 순간에만 유효, 코드리뷰로 캐시 제외 누락 발견돼 반영) |
| `get_stock_credit_info` | companyName(필수), infoType(`COLLATERAL_LOAN`/`MARGIN_REQUIREMENT`/`MARGIN_TRADING`/`SECURITIES_LENDING`), periodMonths(선택, 1~24, infoType=`SECURITIES_LENDING`일 때만) | `LsEtcApiClient`의 4개 메서드(CLNAQ00100/t1411/t1921/t1941)로 1:1 분기. `SECURITIES_LENDING`(t1941)만 periodMonths로 최대 2년 확장 가능(2026-08-13 추가) — `MARGIN_TRADING`(t1921)은 LS API 자체에 기간 파라미터가 없어(연속조회 커서만 지원) 확장 불가로 확인됨, 항상 최근 며칠만 조회 |
| `get_etf_info` | companyName(필수), infoType(`PRICE`/`CONSTITUENTS`) | `CONSTITUENTS`→`LsEtfApiClient.getConstituents()`(t1904, 구성종목 비중은 자주 안 바뀌어 30분 도구 캐시 대상 유지), 그 외(기본 `PRICE`)→`getCurrentPrice()`(t1901, `get_current_price`와 동일하게 30분 도구 캐시 대상에서 제외 — 코드리뷰로 캐시 제외 누락 발견돼 반영) |
| `get_program_trading_summary` | mode(`MARKET_SNAPSHOT`/`TOP_STOCKS`) | `TOP_STOCKS`→`LsProgramApiClient.getTopProgramTradingStocks()`(t1636), 그 외(기본)→`getMarketSnapshot()`(t1640) |
| `get_investor_trend_summary` | mode(`BY_INVESTOR_TYPE`/`BY_MARKET`) | `BY_MARKET`→`LsInvestorApiClient.getMarketComparison()`(t1615), 그 외(기본)→`getInvestorTypeSummary()`(t1601) |
| `get_new_listing_stocks` | periodMonths(선택, 1~24) | `LsEtcApiClient.getNewListings()`(t1403, periodMonths 있으면 최대 2년까지 확장, 2026-08-13 추가) |
| `get_short_selling_trend` | companyName(필수), periodMonths(선택, 1~24) | `LsEtcApiClient.getShortSellingTrend()`(t1927, periodMonths 있으면 최대 2년까지 조회 후 합계·최고일 요약) |
| `get_stock_master_info` | companyName(필수) | `LsEtcApiClient.getStockMasterInfo()`(t8436) |
| `get_industry_info` | marketName(`코스피`/`코스닥`), mode(`CURRENT`/`TREND`/`EXPECTED`), callAuctionSession(`장전`/`장후`, EXPECTED일 때만), periodMonths(선택, 1~24, mode=TREND일 때만) | `TREND`→`LsIndustryApiClient.getTrend()`(t1514, periodMonths 있으면 월봉으로 전환해 최대 24개월 조회 후 최고/최저 요약), `EXPECTED`→`getExpectedIndex()`(t1485, `DateUtil.isCallAuctionTime()` 게이트), 그 외(기본 `CURRENT`)→`getCurrentPrice()`(t1511) |

**`ConfirmedPrice` 확정 시세 패턴 (2026-08-11 라이브 테스트로 도입)** — Gemini가 답변 문장을 작성하며
가격·거래량 숫자를 옮겨 적다가 실제로 다른 숫자를 지어내는 사고가 라이브에서 실측됐다(자릿수
누락, 완전히 다른 숫자 등). "절대 틀리면 안 되는 숫자"는 모델에게 "잘 베껴 써라"라고 프롬프트로
부탁하는 방식이 구조적으로 불안정하다는 결론을 내려, 코드가 직접 보장하는 방식으로 전환했다.
`AiPlanningService`의 private record `ConfirmedPrice`(stockName, stockCode, price, volume) —
`get_current_price`/`get_multi_stock_price` 두 도구만 호출할 때마다 `CopyOnWriteArrayList<ConfirmedPrice>
confirmedCurrentPrices`(`executeTool()`이 `aiToolTaskExecutor`로 동시 실행되므로 스레드 안전 컬렉션
필요)에 실측값을 쌓아두고, 모델의 최종 답변 뒤에 `"\n\n[확인된 시세] %s(%s) %,d원, 거래량 %,d주"`
형식으로 코드가 직접 이어붙인다(모델이 옮겨 적은 본문 숫자와 무관하게 항상 정확). 이 두 도구는
30분 도구 캐시 자체도 우회한다(시세는 그때그때 달라지는 값이라).

**LS증권 REST API 클라이언트 (`infra/ls`, 27개 도구 중 `get_*` 조회 전용 — 6번 섹션의
`LsWebSocketClient`/`LsWebSocketHandler`/`LsReconnectService`/`LsMarketDataListener`/
`LsAuthenticationException`(실시간 체결·호가 WebSocket 계열)과는 완전히 별개 카테고리)**

| 클라이언트 | 엔드포인트(`ls.*-url`) | 공개 메서드 → TR코드 |
|---|---|---|
| `LsAccessTokenProvider` | `${ls.token-url}` | `issueAccessToken()` — 아래 10개 클라이언트가 전부 공유하는 토큰 발급 전용 컴포넌트(WebSocket 쪽 `LsWebSocketClient`는 이걸 안 쓰고 자체 토큰 발급 로직을 유지) |
| `LsMarketDataApiClient` | `market-data-url` | `getCurrentPrice(String stockCode)`→t1102, `getRiskFlags(String stockCode)`→t1404+t1405, `getPivotLevels(String stockCode)`→t1105, `getRecentHistoricalPrices(String stockCode)`/`getHistoricalPrices(String stockCode, Integer periodMonths)`→t1305(periodMonths 없으면 일봉 최근 5건, 있으면 월봉으로 전환해 최대 24개월=2년, 2026-08-13 추가 — open/high/low도 함께 파싱), `getMultiStockPrices(List<String> stockCodes)`→t8407(최대 5종목), `getRecentCallAuctionPrices(String stockCode)`→t1486(최대 5건, 시간대 게이트는 호출부 책임) |
| `LsInvestorTrendApiClient` | `frgr-itt-url` | `getRecentTrend(String stockCode)`/`getTrend(String stockCode, Integer periodMonths)`→t1716(외인기관종목별동향, periodMonths 없으면 최근 10일·최대 5건, 있으면 최대 24개월=2년까지 일별 원본 그대로 반환해 호출부가 합계·최고/최저일 계산, 2026-08-13 추가) |
| `LsInvestInfoApiClient` | `investinfo-url` | `getInvestmentOpinions(String stockCode)`→t3401(최대 5건), `getShareholderMeetingSchedule(String stockCode)`→t3202(`upgu=="09"` 필터, 최대 5건), `getFinancialRanking(String criteria)`→t3341(최대 10건), `getOverseasIndex(String kind, String symbol)`→t3521, `getRecentMarketLiquidityTrend()`/`getMarketLiquidityTrend(Integer periodMonths)`→t8428(periodMonths 없으면 최근 7일·최대 5건, 있으면 최대 24개월=2년, 2026-08-13 추가) |
| `LsHighItemApiClient` | `high-item-url` | `getTopPriceChangeRate()`→t1441, `getTopMarketCap()`→t1444, `getTopVolume()`→t1452, `getTopTradingValue()`→t1463, `getSurgingVolumeVsYesterday()`→t1466, `getTopAfterHoursPriceChangeRate()`→t1481, `getTopAfterHoursVolume()`→t1482 (전부 `List<LsRankingItemDto>`, 최대 10건) |
| `LsSectorApiClient` | `sector-url` | `getThemeConstituentsByName(String themeName)`→t8425(테마명→코드 프로세스 수명 캐시) 후 t1537, `getThemesForStock(String stockCode)`→t1532, `getHotThemes()`→t1533 |
| `LsEtfApiClient` | `etf-url` | `getCurrentPrice(String stockCode)`→t1901, `getConstituents(String stockCode)`→t1904(최대 10건) |
| `LsProgramApiClient` | `program-url` | `getTopProgramTradingStocks()`→t1636(최대 10건), `getMarketSnapshot()`→t1640(gubun=`11` 거래소 전체) |
| `LsInvestorApiClient` | `investor-url` | `getInvestorTypeSummary()`→t1601, `getMarketComparison()`→t1615 |
| `LsEtcApiClient` | `etc-url` | `getCollateralLoanEligibility(String stockCode)`→`CLNAQ00100`(예탁담보융자가능종목현황조회), `getMarginRequirement(String stockCode)`→t1411(증거금율별종목조회), `getMarginTradingTrend(String stockCode)`→t1921(신용거래동향, 최근 5일 — LS API 자체에 기간 파라미터가 없어 확장 불가, 2026-08-13 전수조사로 확인), `getSecuritiesLendingTrend(String stockCode)`/`getSecuritiesLendingTrend(String stockCode, Integer periodMonths)`→t1941(종목별대차거래일간추이, periodMonths 없으면 최근 7일·최대 5건, 있으면 최대 24개월=2년, 2026-08-13 추가), `getNewListings()`/`getNewListings(Integer periodMonths)`→t1403(신규상장종목조회, periodMonths 없으면 최근 6개월·최대 10건, 있으면 최대 24개월=2년·최대 50건, 2026-08-13 추가), `getRecentShortSellingTrend(String stockCode)`/`getShortSellingTrend(String stockCode, Integer periodMonths)`→t1927(공매도일별추이, periodMonths 없으면 최근 7일·최대 5건, 있으면 최대 24개월=2년, 2026-08-13 추가), `getStockMasterInfo(String stockCode)`→t8436(주식종목조회API용) |
| `LsIndustryApiClient` | `industry-url`(`/indtp/market-data`, 기존에 전혀 구현 안 돼 있던 업종 카테고리) | `getCurrentPrice(String marketName)`→t1511(업종현재가), `getRecentTrend(String marketName)`/`getTrend(String marketName, Integer periodMonths)`→t1514(업종기간별추이, periodMonths 없으면 일봉 최근 5건, 있으면 월봉(gubun2=3)으로 전환해 최대 24개월=2년, 2026-08-13 추가), `getExpectedIndex(String marketName, String callAuctionSession)`→t1485(예상지수, 시간대 게이트는 호출부 책임). `marketName`은 `코스피`→`001`/`코스닥`→`301`로 매핑 |

**`LsApiClientSupport`(추상, `infra/ls` 패키지 전용, 코드리뷰 반영)** — 위 10개 클라이언트가
전부 거의 동일하게 복붙하고 있던 요청 빌딩(Authorization/tr_cd/tr_cont 헤더 + `ExternalApiInvoker`
위임)과 응답 필드 파싱을 한 곳으로 모은 베이스 클래스. `protected Map<String, Object>
call(String url, String trCd, Map<String, Object> requestBody, String token, String errorLabel)`
(4개 헤더만 필요한 대다수), 그 오버로드로 `extraHeaders` 인자를 받는 5-인자 버전(`LsEtcApiClient`만
`tr_cont_key` 헤더가 추가로 필요해서 씀), `protected String stringOf(Object)`,
`protected Long parseLong(Object)`, `protected double parseDoubleOrZero(Object)`(실패/누락 시 0.0)를
제공한다. 10개 클라이언트 전부 이 클래스를 상속한다. 예외— `LsInvestorTrendApiClient`는 실패/누락
시 0.0이 아니라 `null`을 돌려주는 자체 `parseDouble(Object): Double`을 그대로 로컬에 유지한다(그
파일의 호출부가 "값 없음"과 "0"을 구분해야 함) — 이 파일만 `parseDoubleOrZero`를 안 쓴다.

> **`parseNullableDouble(Object)` 베이스 클래스로 승격 (코드리뷰 반영)**: 원래
> `LsMarketDataApiClient`에만 있던 private 메서드였는데, `LsEtfApiClient.getCurrentPrice()`가
> per/exhratio 파싱에 똑같이 필요해지면서 `protected`로 `LsApiClientSupport`에 옮기고
> `LsMarketDataApiClient`의 중복 정의는 삭제했다.
>
> **`LsEtfApiClient.getCurrentPrice()`(t1901) 필드 매핑 보강 (LS증권 TR 필드 정정 반영,
> 2026-09)**: `LsCurrentPriceDetailDto`의 per/high52wDate/low52wDate/listingShares/
> foreignExhaustionRate가 t1901OutBlock에 실제로 내려오는데도 지금까지 매핑이 안 돼 있어
> 항상 빈 값이었다. `outBlock`의 `per`/`high52wdate`/`low52wdate`/`listing`/`exhratio`
> 필드를 각각 연결했다(pbr은 ETF에 개념이 없는 필드라 계속 null).

**`infra/ls/dto` 신규 DTO 26개** (기존 실시간 계열의 `LsTickData`/`LsHogaData`/`LsTokenResponse`와는 별개)

| DTO | 필드 |
|---|---|
| `LsCurrentPriceDetailDto` | stockCode, stockName, currentPrice, changeAmount, changeRate, volume, per, pbr, high52w, high52wDate, low52w, low52wDate, listingShares, foreignExhaustionRate, updatedAt |
| `LsMultiStockPriceDto` | stockCode, stockName, price, changeAmount, changeRate, volume |
| `LsPivotLevelDto` | stockCode, pivot, resistance1, support1, resistance2, support2 |
| `LsHistoricalPriceDto` | date, open, high, low(2026-08-13 추가 — 기간 내 최고가/최저가 계산용), close, changeRate, volume, marketCap, foreignNetBuy, individualNetBuy |
| `LsCallAuctionPriceDto` | time, price, changeRate, expectedVolume |
| `LsStockRiskFlagDto` | flagType, reasonCode, date |
| `LsForeignInstitutionalTrendDto` | date, closePrice, individualNetBuyKrx, institutionNetBuyKrx, foreignNetBuyKrx, programTradingVolume, foreignHoldingShares, foreignExhaustionRate, shortSellingVolume, shortSellingValue |
| `LsInvestmentOpinionDto` | date, securitiesFirm, opinionBefore, opinionAfter, targetPriceBefore, targetPriceAfter, closePriceOnDate |
| `LsShareholderMeetingDto` | date, eventName |
| `LsRankingItemDto` | rank, stockCode, stockName, price, changeAmount, changeRate, volume, extraInfo |
| `LsThemeDto` | themeCode, themeName, stats(핫테마 조회에서만 채워짐) |
| `LsThemeConstituentDto` | stockCode, stockName, price, changeAmount, changeRate, volume |
| `LsFinancialRankingDto` | rank, stockCode, stockName, roe, per, pbr, salesGrowthRate |
| `LsOverseasIndexDto` | symbol, name, price, changeAmount, changeRate, date |
| `LsMarketLiquidityDto` | date, customerDepositAmount, marginLoanAmount |
| `LsEtfConstituentDto` | stockCode, stockName, price, changeRate, weight |
| `LsProgramTradingRankDto` | rank, stockCode, stockName, price, changeRate, netBuyValue |
| `LsProgramTradingSnapshotDto` | offerValue, bidValue, netValue |
| `LsInvestorTypeSummaryDto` | individualNetBuy, foreignNetBuy, institutionNetBuy, securitiesNetBuy, insuranceNetBuy, investmentTrustNetBuy |
| `LsMarketInvestorComparisonDto` | marketName, individualNetBuy, foreignNetBuy, institutionNetBuy |
| `LsStockCreditInfoDto` | stockCode, detail |
| `LsNewListingDto` | stockCode, stockName, listedDate, price |
| `LsShortSellingTrendDto` | date, shortSellingVolume, shortSellingValue, shortSellingRatio |
| `LsStockMasterInfoDto` | stockCode, stockName, upperLimitPrice, lowerLimitPrice, isSpac |
| `LsIndustryPriceDto` | industryCode, industryName, indexValue, changeRate |
| `LsIndustryTrendDto` | date, indexValue, changeRate, foreignNetBuy |
| `LsExpectedIndexDto` | expectedIndexValue, changeRate, upperLimitStockCount, lowerLimitStockCount |

**네이버 뉴스 검색 (`infra/naver`, 2026-08-05 Tavily 대체)**
- `NaverNewsApiClient.search(NaverNewsSearchRequest request)` — 엔드포인트 `app.naver.api-url`, 인증은
  NCP API Gateway 방식 헤더(`X-NCP-APIGW-API-KEY-ID`/`X-NCP-APIGW-API-KEY`, 옛 `X-Naver-Client-Id`
  방식이 아님). Naver가 `format=json`을 줘도 실제로는 `text/plain;charset=UTF-8`로 응답해서
  `MappingJackson2HttpMessageConverter`가 `text/plain`도 처리하도록 별도 등록.
- 회사명은 항상 단독으로만 검색하고(topic과 합쳐서 검색하면 네이버 자체 정확도 정렬이 깨짐,
  라이브 테스트로 확인) topic은 응답을 받은 뒤 필터링 단계에서만 사용. 정렬은 topic이 있으면
  `date`(최신순), 없으면 `sim`(정확도순, 2026-08-05 확정 트레이드오프 유지).
  기간 필터는 Naver API 파라미터가 아니라 응답을 받은 뒤 `pubDate` 기준으로 클라이언트에서
  직접 거른다(`NewsRelevanceMatcher.resolvePeriodDays()` 기준).
  신뢰 도메인 필터(`NewsRelevanceMatcher.SECURITIES_NEWS_DOMAINS`, 정확 일치/서브도메인만 허용 —
  "fakesedaily.com" 같은 유사 도메인 차단)와 관련성 필터(제목/본문 요약 중 하나에 회사명 매칭 +
  topic 있으면 `NewsRelevanceMatcher.topicMatchesAnyToken()`까지 통과)를 모두 거친 뒤 최대 5건 반환.
- `NaverNewsSearchRequest`(companyName, topic, periodDays), `NaverNewsSearchResponse`(results: `NaverNewsResult`(title, description, link, pubDate, outlet) 리스트)
- `NaverNewsApiClient.searchByOutlet(String outletDomain)` (feature/ai-news 추가, 8-19 참고) —
  고정 검색어 목록(`GENERAL_MARKET_QUERIES`: "증시"→"코스피"→"주가"→"코스닥" 순서, 2026-08-24
  다중화)으로 넉넉히 받아온 뒤 처음부터 지정된 언론사 도메인 하나로만 걸러내고,
  `NewsRelevanceMatcher.isMarketRelevant()`(2026-08-24 추가, 아래 참고)로 진짜 시황 관련
  기사인지 한 번 더 거른 다음에야 최대 5건으로 자른다(29개 신뢰 도메인 전체에서 5건으로 먼저
  자르는 `search()`의 순서를 그대로 따르면 원하는 언론사 기사가 5건 안에 못 들어 0건이 되는
  문제가 있어 순서를 뒤집었다).

**global 신규/변경 유틸리티**
- `RedisAiToolCacheService`(§7 redis-service에도 등록) — `getCachedResult(Long sessionId, String toolKey)`,
  `cacheResult(Long sessionId, String toolKey, String result)`. 키 `ai:tool:{sessionId}:{toolKey}`, TTL 30분.
- `ExternalApiInvoker` — `static <T> T call(Supplier<T> apiCall, String logMessage, Object... logArgs)`.
  `GeminiApiClient`/`DartApiClient`/`NaverNewsApiClient`/LS REST 클라이언트 전체가 반복하던
  try-catch(`RestClientException`→로그→`CustomException(ErrorCode.EXTERNAL_API_ERROR, e)`) 패턴을 공용화.
- `NewsRelevanceMatcher` — `SECURITIES_NEWS_DOMAINS`(신뢰 언론사 도메인 29개), `KNOWN_ALIASES`(현재
  `삼성전자`→`삼전`만 등록, 필요시 확장), `MAX_PERIOD_DAYS`(730일=2년, 2026-08-11에 90일에서 확장),
  `titleMatchesToken()`, `topicMatchesAnyToken()`(topic을 공백 기준 토큰으로 쪼개 하나만 일치해도 인정,
  "및"/"관련" 등 `TOPIC_STOPWORDS` 제외), `resolvePeriodDays(Integer periodDays)`,
  `OUTLET_NAMES`(도메인→한글 언론사명 맵, feature/ai-news 추가 시 `NaverNewsApiClient`의 private
  map을 이 클래스로 승격 — 8-19 참고), `matchesDomain(String host, String domain)`(같은 이유로 승격,
  `host`가 `domain` 자체이거나 하위 도메인일 때만 true), `isMarketRelevant(String title)`
  (feature/ai-news 추가, 2026-08-24 — `MARKET_KEYWORDS`(증시/코스피/코스닥/주가 등 시황 관련
  단어) 중 하나라도 제목에 있어야 통과. 처음엔 본문 요약까지 같이 확인했는데, 본문에 한 줄만
  스친 기사까지 통과해버려 "시황 브리핑이면 제목부터 증시 얘기여야 한다"는 판단에 따라
  제목만 보도록 좁혔다 — `searchByOutlet()`이 회사명 없이 "증시" 단일 검색어만으로는
  걸러내지 못하는 무관한 기사를 잡기 위해 추가)
- `DateUtil` — `isCallAuctionTime()`(08:30~09:00, 15:20~15:30 KST 평일), `isAfterHoursTradingTime()`(15:30~18:00
  KST 평일). 시간대가 아닐 때 해당 LS 도구 호출 자체를 막고 안내 문구로 대체하는 "시간대 게이트"용 —
  둘 다 `Asia/Seoul` 고정, 테스트 주입용 `ZonedDateTime` 오버로드 있음
- `RestClientConfig` — `RestClient.Builder` 빈 하나(연결 5초/응답 30초 타임아웃 고정). Gemini/DART/Naver/LS
  REST 클라이언트 전부가 이 빈을 주입받아 각자 `.build()`(Naver만 `.clone()...build()`)로 독립 인스턴스 생성

### 8-10. feature/ai-news

| 구분 | 이름 |
|---|---|
| Controller | `AiNewsController` |
| 엔드포인트 | `GET /api/ai/news` |
| Service | `AiNewsService` — `searchNews(String keyword)` |
| Request DTO | `NewsSearchRequest`(keyword) |
| Response DTO | `NewsSearchResponse`(title, url, summary, publishedAt) |

### 8-11. feature/simulation / feature/simulation-integration

> **2차 PR(`feature/simulation-integration`) 반영**: 1차 PR은 `GeminiApiClient`/
> `DartApiClient`/`infra/naver/*`가 dev에 아직 없어(`feature/ai-planning` 미병합)
> 순수 계산 로직(`ScenarioCalculator`)과 조회 API(`GET`)만 구현했었다.
> `feature/ai-planning`이 dev에 병합된 뒤 이 브랜치에서 `POST /api/simulations`
> (`runSimulation`)을 이어 구현했다 — Gemini에는 stockCode/종목명만 주고
> 시나리오별 월 복리 성장률(스칼라 double 3개, JSON 응답)을 근거와 함께 요청하며
> (`SimulationService.MonthlyGrowthRates`, private record — JSON 파싱 실패는
> `SCENARIO_DATA_PARSE_ERROR` 재사용), DART 재무 데이터(연간+최근분기)와 네이버
> 뉴스 조회는 Gemini 호출과 무관하게 별도로 수행해 `dart_data`/`news_data`
> 컬럼에만 원본을 저장한다. `stockCode`→`corpCode` 변환은 `DartApiClient`에
> stockCode 전용 메서드가 없어 `StockNameResolver.resolveStockName()`으로 얻은
> 종목명을 `DartApiClient.resolveCorpCodeByName()`에 넘기는 방식으로 처리한다
> (corpCode를 못 찾으면 dartData는 null — 두 컬럼 모두 schema.sql상 NULL 허용).
> DART 연간+최근분기 병렬 조회는 `DartApiClient.fetchFinancialIndicatorCategories()`와
> 동일하게 `Executors.newVirtualThreadPerTaskExecutor()`를 쓴다(AI 상담 전용인
> `aiToolTaskExecutor` 빈은 재사용하지 않음 — 스코프가 다른 기능이라).
>
> **트랜잭션 분리(코드리뷰 반영)**: `runSimulation()`은 `AiPlanningService.sendMessage()`와
> 동일한 이유로 `@Transactional`을 걸지 않는다 — Gemini/DART/네이버 호출을 DB 트랜잭션
> 안에 묶으면 커넥션을 수 초씩 점유해 무관한 API까지 커넥션 풀 고갈 영향을 받을 수 있다.
> `SimulationService`는 이제 클래스 레벨 `@Transactional`을 두지 않고(`AiPlanningService`와
> 동일), `getMySimulations`/`getSimulation` 각각에 `@Transactional(readOnly = true)`를 개별로
> 붙인다. 외부 호출 전/후 경계는 `loadHistory()`/`saveTurn()`과 동일한 self-invocation
> 패턴(`@Autowired @Lazy private SimulationService self`)으로 나눈다 — `resolveStockName(Long
> userId, String stockCode)`(`@Transactional(readOnly = true)`, 사용자 존재 확인 + 종목명 조회를
> 외부 호출 전에 끝냄)와 `saveSimulation(Long userId, SimulationRequest request, String
> stockName, ScenarioSetDto scenarioSet, String dartDataJson, String newsDataJson)`
> (`@Transactional`, 외부 호출 성공 후 저장 + 알림 발송)로, 둘 다 `runSimulation()` 외부에서
> 호출할 일은 없지만 self 프록시를 타야 해서 public이다. `saveSimulation()`은
> `userRepository.getReferenceById()`로 User FK를 채운다(`resolveStockName()`에서 이미 존재를
> 확인했으므로 `NotificationService.notify()`와 동일하게 재조회 없이 참조만 사용).
>
> **SIMULATION 알림 추가(코드리뷰 반영)**: 저장 직후 `NotificationService.notify(userId,
> NotificationType.SIMULATION, title, content)`를 호출한다 — `AiPlanningService`가 아니라
> `AiNewsService.generateBriefingForUser()`와 동일한 패턴(`AiPlanningService`는 알림을 보내지
> 않음). title은 `"{stockName} 목표 도달 시뮬레이션이 완료됐어요"`, content는 베이스 시나리오
> 기준 `baseReachDate`가 있으면 `"베이스 시나리오 기준 목표 도달 예상일: {날짜}"`, 없으면(기간 내
> 미도달) `"베이스 시나리오 기준으로는 설정하신 기간 내 목표 도달이 어려울 것으로 예상돼요."`
> (`SimulationService.buildReachDateNotificationContent()`). `NotificationType.SIMULATION`의
> 첫 실사용이다.

| 구분 | 이름 |
|---|---|
| Controller | `SimulationController` |
| 엔드포인트 | `POST /api/simulations`, `GET /api/simulations`, `GET /api/simulations/{simulationId}` |
| Service | `SimulationService` — `getMySimulations(Long userId)`, `getSimulation(Long userId, Long simulationId)`, `runSimulation(Long userId, SimulationRequest request)`. `resolveStockName(Long userId, String stockCode)`/`saveSimulation(...)`도 public인데, `AiPlanningService.loadHistory()`/`saveTurn()`과 동일하게 self-invocation으로 트랜잭션 경계를 나누기 위한 것 — 외부에서 호출할 일은 없다 |
| Request DTO | `SimulationRequest`(stockCode, investmentAmount, targetAmount, targetMonths) — targetMonths는 1~12 (`@Min(1) @Max(12)`) |
| Response DTO | `SimulationResponse`(simulationId, stockCode, stockName, investmentAmount, targetAmount, targetMonths, bestScenario, baseScenario, worstScenario, bestReachDate, baseReachDate, worstReachDate, createdAt) — 정적 팩토리 `of(Simulation, List<ScenarioPointDto> best, List<ScenarioPointDto> base, List<ScenarioPointDto> worst)` |
| 내부 DTO | `ScenarioPointDto`(date: `LocalDate`, value: `long`) — 시나리오 곡선 한 포인트. `date`는 매월 1일로 정규화. `value`는 `investmentAmount` 복리 계산 결과인 포트폴리오 평가금액(원 단위, `Math.round()` 반올림)이며 종목 주당가(`price`)가 아니므로 필드명을 `price`가 아닌 `value`로 둔다(코드베이스 전역에서 `price`는 이미 "주당 시장가" 의미로 쓰이고 있어 혼동 방지). 위치는 `domain/stock/dto/StockPriceDto.java`와 동일하게 `domain/ai/dto/` 바로 아래. |
| 내부 DTO | `ScenarioDataJson`(best: `List<ScenarioPointDto>`, base: `List<ScenarioPointDto>`, worst: `List<ScenarioPointDto>`) — `simulations.scenario_data` JSON 컬럼의 저장 형태를 그대로 미러링하는 Jackson 매핑 전용 record. `SimulationService`가 조회 시 이 타입으로 역직렬화한다. `domain/ai/dto/` |
| 계산 엔진 | `ScenarioCalculator`(`domain/ai/service`, 정적 유틸리티 클래스 — Spring 빈 아님) — `public static ScenarioSetDto calculate(long investmentAmount, double bestMonthlyGrowthRate, double baseMonthlyGrowthRate, double worstMonthlyGrowthRate, long targetAmount, int targetMonths, LocalDate startDate)`. 순서: ① best/worst는 `[-0.08, 0.08]`, base는 `[-0.02, 0.02]`로 각각 clamp(상수 `MAX_MONTHLY_GROWTH_RATE_WIDE`/`MAX_MONTHLY_GROWTH_RATE_NARROW`) → ② clamp된 세 값을 원래 라벨과 무관하게 내림차순 정렬해 큰 값부터 best/base/worst로 재배정(넓은 clamp 폭 때문에 라벨 순서가 뒤집힐 수 있어 라벨을 신뢰하지 않음, best≥base≥worst 보장) → ③ 재배정된 값으로 각각 month 0~targetMonths 곡선 생성(`value = investmentAmount * (1+rate)^month`, 반올림) → ④ 곡선에서 `value >= targetAmount`를 처음 만족하는 date를 reachDate로 산출(`investmentAmount >= targetAmount`면 month 0, 못 도달하면 null). `startDate`는 순수 함수 보장을 위한 외부 주입 파라미터(내부에서 `LocalDate.now()` 호출 금지) — 호출 측(다음 PR의 `runSimulation`)이 `LocalDate.now()`를 넘긴다. |
| 내부 DTO | `ScenarioSetDto`(bestPoints/basePoints/worstPoints: `List<ScenarioPointDto>`, bestReachDate/baseReachDate/worstReachDate: `LocalDate`) — `ScenarioCalculator.calculate()`의 반환 타입. `domain/ai/dto/` |

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
| Service | `AdminTradeService` — `getTrades(String query, OrderStatus status, OrderType orderType, PriceType priceType, String stockCode, LocalDateTime from, LocalDateTime to, Pageable pageable)`, `getTradeDetail(Long orderId)` |
| Response DTO | `AdminTradeResponse`(userName, loginId, `order`: `OrderHistoryResponse` 재사용) |

> **코드리뷰 반영**: 처음에는 `AdminTradeResponse`가 `OrderHistoryResponse`와 거의 같은 필드
> (stockCode/orderType/priceType/orderPrice/execPrice/quantity/status/orderedAt/executedAt)를
> 중복 정의했다. `AdminUserDetailResponse`(8-17)가 이미 `OrderHistoryResponse`를 내부 필드로
> 재사용하고 있어 동일한 패턴으로 통일 — 주문 자체의 필드는 `order: OrderHistoryResponse`로
> 위임하고, 관리자 화면에만 필요한 `userName`/`loginId`만 이 레코드가 따로 갖는다.

> **검색·필터 추가 (feature/admin-api-p0, ADMIN_API_BACKEND_HANDOFF.md 3.3)**: `GET
> /api/admin/trades`에 `query`(주문번호/회원 아이디/계좌번호/종목코드/종목명 통합검색),
> `status`, `orderType`, `priceType`, `stockCode`(정확일치), `from`~`to`(orderedAt 구간) 쿼리
> 파라미터를 추가했다. 전부 선택값이며 비어 있으면 기존과 동일하게 전체 목록을 반환한다.
> 기본 정렬은 `orderedAt,desc`. `OrderRepository.searchOrdersWithUser(...)`가 실제 조건을
> 처리하고, `findAllOrdersWithUser(Pageable)`는 더 이상 쓰지 않지만 다른 호출부가 없어
> Repository 메서드 자체는 남겨뒀다.

> **주문 강제취소 추가 (2026-09-07, handoff 문서 9번 "구현 전 결정이 필요한 정책" 2번 —
> 정책 확정 전 우선 구현하기로 함)**: `PATCH /api/admin/trades/{orderId}/cancel`(body:
> `reason`) 추가. `AdminTradeService.cancelTrade(Long adminUserId, Long orderId,
> AdminOrderCancelRequest request)`가 `OrderService.adminCancelOrder(Long adminUserId,
> Long orderId, String reason)`(신규)에 위임한다. `OrderService.cancelOrder()`(8-6)와
> 로직은 거의 같지만 소유자(userId) 검증이 없어(관리자는 어느 유저의 주문이든 취소 가능해야
> 함) `findByOrderIdAndUserIdForUpdate` 대신 `findByIdForUpdate`를 쓰고, 계좌 정지 여부도
> 확인하지 않는다. 감사 로그는 `audit_logs` 테이블 승인 전이라 SLF4J 로그로만 최소한의
> 추적성을 남긴다 — 테이블이 생기면 영구 저장으로 교체해야 한다.

### 8-16. feature/admin-account (v8 신규)

| 구분 | 이름 |
|---|---|
| Controller | `AdminAccountController` |
| 엔드포인트 | `GET /api/admin/accounts`, `GET /api/admin/accounts/{accountId}`, `PATCH /api/admin/accounts/{accountId}/status` |
| Service | `AdminAccountService` — `getAccounts(String query, AccountStatus status, Pageable pageable)`, `getAccountDetail(Long accountId)`, `updateAccountStatus(Long accountId, AdminAccountStatusRequest request)` |
| Request DTO | `AdminAccountStatusRequest`(status) |
| Response DTO | `AdminAccountDetailResponse`(accountId, userName, accountNumber, balance, frozenBalance, baseBalance, status) |

> **계좌 목록·검색 추가 (feature/admin-api-p0, ADMIN_API_BACKEND_HANDOFF.md 3.4)**: `GET
> /api/admin/accounts`를 추가했다. `query`(계좌 소유자 아이디/이름/계좌번호 통합검색),
> `status`는 선택 파라미터, 기본 정렬은 `createdAt,desc`. 목록 전용 DTO를 새로 만들지 않고
> 상세와 동일한 `AdminAccountDetailResponse`를 그대로 재사용한다(admin-trade가 목록/상세를
> 하나의 DTO로 통일한 것과 동일한 이유).

> **정지 시 PENDING 주문 일괄 취소 (코드리뷰 반영, v8)**: `updateAccountStatus()`가
> `account.suspend()`만 하고 그 계좌의 기존 PENDING 지정가 주문을 그대로 두면, `accounts.status`가
> `SUSPENDED`인지 확인하지 않는 `OrderExecutionService.execute()`가 tick마다 그대로 체결시켜
> CLAUDE.md 8번의 "정지 시 매수·매도 주문 차단"이 지켜지지 않는다. 게다가 `OrderService.
> cancelOrder()`는 계좌가 `SUSPENDED`면 취소 요청 자체를 막아서 사용자가 그 주문을 스스로
> 취소할 수도 없다. 그래서 `updateAccountStatus()`가 SUSPENDED로 전환하는 같은 트랜잭션 안에서
> `OrderService.cancelAllPendingOrdersForSuspension(account)`(8-6 참고)를 호출해 그 계좌의
> PENDING 주문을 전부 취소한다. `AdminAccountService`는 이제 `AccountRepository`뿐 아니라
> `OrderService`도 주입받는다 — Repository를 직접 잡아 취소 로직을 복붙하지 않고 order 도메인의
> 기존 서비스(잔고 동결 해제·Redis `pending:orders` 정리 포함)를 그대로 재사용한다(CLAUDE.md
> 4번 "도메인 간 직접 참조 대신 서비스 계층을 통해 호출").

### 8-17. feature/admin-user (v8 신규)

| 구분 | 이름 |
|---|---|
| Controller | `AdminUserController` |
| 엔드포인트 | `GET /api/admin/users`, `GET /api/admin/users/{userId}`, `PATCH /api/admin/users/{userId}/status` |
| Service | `AdminUserService` — `getUsers(String query, UserStatus status, Role role, Pageable pageable)`, `getUserDetail(Long userId)`, `updateUserStatus(Long adminUserId, Long userId, AdminUserStatusRequest request)` |
| Request DTO | `AdminUserStatusRequest`(status) |
| Response DTO | `AdminUserListResponse`(userId, loginId, name, email, role, status, createdAt), `AdminUserDetailResponse`(기본정보 필드 + `accounts`: `List<AccountInfoResponse>` 재사용 + `holdings`: `List<HoldingResponse>` 재사용 + `orders`: `List<OrderHistoryResponse>` 재사용) |

> **검색·필터 추가 (feature/admin-api-p0, ADMIN_API_BACKEND_HANDOFF.md 3.2)**: `GET
> /api/admin/users`에 `query`(회원번호/아이디/이름/이메일 통합검색), `status`, `role` 쿼리
> 파라미터를 추가했다. 전부 선택값이며 비어 있으면 기존과 동일하게 전체 목록을 반환한다.
> 기본 정렬은 `createdAt,desc`. `UserRepository.searchUsers(...)`가 실제 조건을 처리하고,
> `findAllByIsActiveTrue(Pageable)`는 더 이상 이 API가 쓰지 않지만 다른 호출부가 없어
> Repository 메서드 자체는 남겨뒀다.
>
> `AdminUserDetailResponse`는 기존 마이페이지용 DTO(`AccountInfoResponse`, `HoldingResponse`, `OrderHistoryResponse`)를 그대로 내부 필드로 재사용한다 — 동일한 형태의 응답 DTO를 중복 정의하지 않는다.
>
> **탈퇴 회원 조회 추가 (2026-09-07, handoff 문서 5.4 — 3가지 옵션 중 정책 확정 전 옵션2
> "익명화된 탈퇴 이력만 별도 조회"로 우선 구현하기로 함)**: `GET /api/admin/users/withdrawn`
> 추가. `AdminUserService.getWithdrawnUsers(Pageable pageable)` → `UserRepository.
> findAllByIsActiveFalse(pageable)`. 응답 DTO는 `AdminWithdrawnUserResponse`(userId, loginId,
> name, deletedAt) — `deactivate()`가 이미 loginId/name/email을 익명화했으므로 그 값을 그대로
> 노출한다. 기본 정렬은 `deletedAt,desc`. 최종 정책이 "완전 제외"나 "WITHDRAWN 정식 상태
> 도입"으로 바뀌면 이 엔드포인트/메서드를 그에 맞게 제거하거나 교체해야 한다.
>
> 유저 1명이 계좌를 최대 3개(A/B/C)까지 가질 수 있어(`feature/mypage-account`) `account`(단수) 대신
> `accounts`(복수, 전체 계좌 리스트)로 정의한다. `holdings`/`orders`도 계좌별로 나누지 않고
> 유저가 가진 **모든 계좌를 합산**해서 보여준다 — 개별 계좌 단위로 파고드는 조회는
> `feature/admin-account`(8-16, `GET /api/admin/accounts/{accountId}`)가 담당한다.
> `AdminUserService.buildDetail()`이 `AccountRepository.findAllByUserId()`로 계좌 목록을 구한 뒤,
> 계좌 ID 리스트를 그대로 `HoldingValuationService.getHoldingValuations(List<Long> accountIds)` /
> `OrderRepository.findAllByAccountIdInOrderByOrderedAtDesc(accountIds)`에 넘겨 배치 조회한다
> (코드리뷰 반영 — 계좌별로 N번 나눠 조회하던 것을 IN 절 조회 1번으로 합쳤다).
> `orders`는 배치 조회 쿼리 자체가 `orderedAt` 내림차순으로 정렬해서 반환하므로 애플리케이션
> 레벨에서 다시 정렬하지 않는다. `HoldingValuationService.getHoldingValuations(Long accountId)`
> (마이페이지용, 계좌 1개)와 `getHoldingValuations(List<Long> accountIds)`(관리자용, 계좌 N개)는
> 오버로드로 공존한다 — 후자는 계좌가 여러 개 섞이면 같은 종목코드가 여러 계좌에 걸쳐 나올 수
> 있어 시세 배치 조회 전 `distinct()`를 거친다는 점이 전자와 다르다.
>
> 목록 조회(`getUsers`)는 `UserRepository.findAllByIsActiveTrue(pageable)`을 써서 탈퇴
> (`deactivate()`) 유저를 제외한다 — `deactivate()`가 loginId/name/email을 익명화해버려
> 관리자 목록에 노출돼도 식별할 수 없기 때문이다(코드리뷰 반영).
> `AdminUserService.findUser()`(상세 조회 `getUserDetail`·상태변경 `updateUserStatus`가 공용으로
> 쓰는 내부 메서드)도 `UserRepository.findByUserIdAndIsActiveTrue(userId)`로 동일하게 막는다 —
> 목록에는 안 보이는데 userId를 직접 넣으면 상세 조회·상태변경이 되는 불일치를 없애기 위해서다.
> 탈퇴 유저에 대해서는 존재 여부를 굳이 구분해서 알려주지 않고 미가입 userId와 동일하게
> `USER_NOT_FOUND`(404)로 응답한다.
>
> **정지(SUSPENDED) 가드 (코드리뷰 반영, v8)**: `updateUserStatus()`가 검증 없이 아무 유저나
> SUSPENDED로 바꿀 수 있으면, 관리자가 (1) 본인 계정을 정지시키거나 (2) 활성 상태인 마지막
> ADMIN을 정지시키는 경우 관리자 전원이 `/api/admin/**`에서 즉시 잠기고(다음 요청부터
> `CustomUserDetailsService`가 `USER_SUSPENDED`로 막음) DB를 직접 고치지 않는 한 되돌릴 방법이
> 없다. 이를 막기 위해 `AdminUserController`가 `SecurityUtil.getCurrentUserId()`로 요청을 보낸
> 관리자의 userId를 꺼내 `adminUserId`로 넘기도록 시그니처를 `updateUserStatus(Long userId,
> AdminUserStatusRequest request)`에서 `updateUserStatus(Long adminUserId, Long userId,
> AdminUserStatusRequest request)`로 바꿨다(`AdminInquiryService.answerInquiry(Long adminUserId,
> Long inquiryId, AdminInquiryAnswerRequest request)`, 8-18과 동일한 패턴 — 서비스가
> `SecurityContext`에 직접 의존하지 않도록 컨트롤러에서 꺼내 파라미터로 넘긴다).
>
> `updateUserStatus()`는 SUSPENDED 요청을 내부 `suspend(Long adminUserId, User targetUser)`로
> 위임하는데, `targetUser.getStatus()`가 이미 `SUSPENDED`면 곧바로 반환한다 — 같은 요청이
> 재시도(타임아웃 후 재전송 등)로 두 번 들어와도 두 번째 호출이 "활성 admin이 이거 하나뿐이라
> 정지 못 함" 같은 엉뚱한 예외를 던지지 않고 멱등하게 통과하도록 하기 위함이다. 아직 ACTIVE인
> 경우에만 `validateSuspendable(Long adminUserId, User targetUser)`를 거쳐 ①
> `targetUser.getUserId().equals(adminUserId)`면 `ErrorCode.SELF_STATUS_CHANGE_NOT_ALLOWED`,
> ② `targetUser.getRole() == Role.ADMIN`이면 `UserRepository.
> findAllByRoleAndStatusAndIsActiveTrueForUpdate(Role.ADMIN, UserStatus.ACTIVE)`로 활성 ADMIN
> 행들에 비관적 락을 건 뒤 그 목록 크기가 1 이하면 `ErrorCode.LAST_ADMIN_SUSPEND_NOT_ALLOWED`를
> 던진다. 락 없이 단순 `count` 쿼리만 썼다면, 활성 ADMIN이 정확히 2명일 때 서로 다른 admin을
> 동시에 정지시키는 두 요청이 각자 "정지 전 카운트=2"를 보고 둘 다 통과해버려 활성 admin이
> 0명이 되는 경쟁 상태가 가능했다 — `AccountRepository.findAllByUserIdForUpdate`와 동일한
> 패턴(잠금 대상 행 목록을 그대로 개수 확인에도 재사용)으로 해결했다. ACTIVE로 되돌리는
> 요청(`activate()`)은 위험하지 않으므로 이 검증을 거치지 않는다.
>
> `User.deactivate()`(본인 탈퇴, 아직 어디서도 호출되지 않음)에는 향후 본인 탈퇴 기능을 구현할
> 때 활성 상태인 마지막 ADMIN 자기 탈퇴로 동일한 lockout이 재현되지 않도록, 호출 전 같은 검증을
> 거쳐야 한다는 주의 주석만 남겨뒀다 — 아직 존재하지 않는 호출부를 위한 재사용 가능한 가드
> 컴포넌트를 미리 만들지는 않는다(CLAUDE.md — 가상의 미래 요구사항을 위해 설계하지 않음).

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
>
> `InquiryRepository.findAllByOrderByStatusDescCreatedAtDesc()`(무인자, `List` 반환 —
> `feature/inquiry`의 `InquiryRepositoryIntegrationTest`가 이미 사용 중이라 그대로 둠)와 같은
> 정렬 기준의 `Pageable` 오버로드는 이름을 분리하지 않고 `findAllByOrderByStatusDescCreatedAtDesc
> (Pageable)`(`Page<Inquiry>` 반환)로 오버로드한다. `AdminInquiryResponse.from()`이
> `inquiry.getUser()`를 참조하므로 파생 쿼리 대신 `join fetch i.user`를 쓰는 `@Query`로 작성해
> `getInquiries()` 목록 조회 시 페이지당 N+1 SELECT가 발생하지 않도록 한다.
>
> **N+1 방지 (코드리뷰 반영, v8)**: `Pageable` 오버로드는 처음에 파생 쿼리(메서드 이름만으로
> 자동 생성되는 쿼리) 그대로였는데, `AdminInquiryResponse.from()`이 목록의 각 `Inquiry`마다
> `inquiry.getUser()`(LAZY)를 호출해 페이지 크기만큼 추가 SELECT가 발생했다. `AdminTradeService.
> findAllOrdersWithUser`와 동일한 패턴으로 `@Query`에 `join fetch i.user`를 추가해 한 번의
> 쿼리로 즉시 로딩한다 — `@Query`를 쓰면 메서드 이름의 `OrderBy`는 더 이상 자동 파싱되지 않으므로
> 원래 정렬 기준(`status desc, createdAt desc`)을 JPQL `order by` 절로 명시했다. `user_id`는
> `NOT NULL`(FK `ON DELETE CASCADE`)이라 `inquiry`는 항상 `user`를 가지므로 `left join fetch`
> 대신 `join fetch`(inner)를 쓴다. `@Query`에 `fetch`가 섞이면 `Page` 카운트 쿼리를 자동
> 유도하기 어려우므로 `countQuery`를 명시적으로 지정한다(`dev` 병합 시 정리, v9).
>
> **재답변(덮어쓰기) 정책**: `answerInquiry`는 대상 문의가 이미 `ANSWERED`여도 소유권/상태
> 검증 없이 그대로 `Inquiry.answer()`를 호출해 기존 답변을 덮어쓴다 — 오타 정정 등 관리자가
> 답변을 다시 보내야 하는 상황을 막지 않기 위한 의도적 선택이다(`admin-user`의 `updateUserStatus`
> 같은 멱등/잠금 가드는 두지 않는다).

### 8-19. feature/ai-news (2026-08-24 신규 — 맞춤형 뉴스 브리핑)

AI 재무설계사(`feature/ai-planning`)와 달리 대화형이 아니다. 사용자가 언론사를 하나
설정해두면(2026-08-24 확정 — 한 번에 하나만 선택 가능, 키워드 입력 없음), 스케줄러가
매일 그 언론사의 오늘자 시황 기사를 모아 Gemini로 요약해두고 알림까지 보낸다.
`feature/ai-planning`이 아직 `dev`에 미병합이라 `NaverNewsApiClient`/`GeminiApiClient`에
의존하는 이 기능도 `feature/ai-planning` 병합 이후에야 `dev`로 합류할 수 있다
(`feature/simulation-integration`과 동일한 제약).

| 구분 | 이름 |
|---|---|
| Controller | `AiNewsController` |
| 엔드포인트 | `GET /api/ai/news/outlets`(선택 가능한 언론사 목록), `GET /api/ai/news/settings`(내 설정 조회), `PUT /api/ai/news/settings`(언론사 설정/변경), `GET /api/ai/news/briefings/today`(오늘의 브리핑 조회) |
| Service | `AiNewsService` — `getSelectableOutlets()`, `getMySetting(Long userId)`, `updateMySetting(Long userId, String outletDomain)`, `getTodayBriefing(Long userId)`, `generateDailyBriefings()`(`@Scheduled(cron = "0 0 7 * * *", zone = "Asia/Seoul")`), `generateBriefingForUser(NewsBriefingSetting setting, LocalDate today)`(`@Transactional`, 사용자 1명분 생성 — `self` 프록시로만 호출), `generateVerifiedSummary(NaverNewsSearchResponse newsResponse, String outletName)`(요약+근거검증+재생성 오케스트레이션, 2026-08-24 추가), `buildArticlesText(...)`, `requestSummary(...)`, `isGrounded(String articles, String summary)`(근거검증 전용 Gemini 호출, 2026-08-24 추가) |
| Request DTO | `NewsBriefingSettingRequest`(outletDomain) |
| Response DTO | `NewsOutletResponse`(outletDomain, outletName), `NewsBriefingSettingResponse`(outletDomain, outletName), `NewsBriefingResponse`(outletDomain, outletName, briefingDate, content, `sources`: `NewsSourceLinkDto` 리스트, 2026-08-24 추가) |
| 내부 DTO | `NewsSourceLinkDto`(title, link, outlet) — `news_briefings.source_links` JSON 컬럼 미러링용(`ScenarioDataJson`과 동일 패턴), `domain.ai.dto` 소속 |
| Entity/Repository | 1-1/1-2 참고 (`NewsBriefingSetting`/`NewsBriefingSettingRepository`, `NewsBriefing`/`NewsBriefingRepository`) |

> **최초 스펙과 달라진 점**: 처음 작업 지시 문서는 "Tavily 즉석 검색, DB 저장 없음"이라는
> 4개 파일(Controller/Service/Request/Response)짜리 단순 검색 기능으로 적혀 있었다. 하지만
> Tavily는 이미 2026-08-05~06에 네이버로 전량 교체·삭제된 상태였고(CLAUDE.md 2번 항목), 사용자와
> 협의 결과 실제로 원하는 건 "AI 재무설계사처럼 채팅하는 것"이 아니라 "설정해둔 언론사를 매일
> 알아서 요약해주는 비서"였다(2026-08-24 확정). 그래서 `NewsSearchRequest`/`NewsSearchResponse`
> 대신 설정(`NewsBriefingSettingRequest`/`Response`)과 결과 조회(`NewsBriefingResponse`) DTO로
> 이름을 바꿨고, `news_briefing_settings`/`news_briefings` 테이블과 스케줄러를 추가로 설계했다.
> schema.sql v5→v6에서 "Tavily 즉석 검색으로 전환"하며 제거했던 `market_briefings` 테이블의
> 개념적 후속이지만, 그때와 달리 "언론사 선택 기반 자동 생성" 방식이라 완전히 새로 설계했다.

> **`NaverNewsApiClient.searchByOutlet()` 신설 이유**: 기존 `search()`는 회사명이 반드시
> 있어야 하고, 신뢰 도메인 29곳 전체에서 먼저 5건으로 잘라낸 뒤에야 걸러내는 구조라 "언론사
> 하나만 종합적으로 보고 싶다"는 요청에 그대로 재사용하면 결과가 자주 0건이 될 위험이 있었다
> (2026-08-24 설계 논의, `search()`는 건드리지 않고 옆에 전용 메서드만 추가했다 — ai-planning
> 쪽 동작에 영향 없음). 언론사 도메인→한글명 매핑(`OUTLET_NAMES`)과 도메인 매칭
> (`matchesDomain()`)은 이 기능에서 "이름으로 선택 → 도메인 변환"이라는 반대 방향으로도
> 필요해져서 `NaverNewsApiClient` 전용 private 멤버였던 것을 `NewsRelevanceMatcher`(공용
> 위치)로 승격했다.

> **알림 연동**: 브리핑 생성 시 `NotificationService.notify(userId, NotificationType.NEWS, ...)`를
> 함께 호출한다(2026-08-24 확정). `NotificationType`에 `NEWS` 값 추가, `notifications.type`
> ENUM에도 `NEWS` 추가(schema.sql v12, `news_briefing_migration.sql`).

> **`searchByOutlet()` 페이지네이션 (2026-08-24 추가)**: 첫 실측 때 29곳 중 16곳이 0건으로
> 나왔는데, 500건(5페이지)까지 더 파보니 그중 15곳은 실제로 오늘 기사가 있었고 전부 오늘
> 날짜였다(과거로 새지 않음, 500건 안에서는 "증시"가 워낙 흔한 검색어라 하루 안에서 다 채워짐).
> 그래서 `MAX_OUTLET_PAGES`(5)까지, 이 언론사 몫(`MAX_RESULTS`)을 채우면 그 즉시 멈추는
> early-exit 방식으로 페이지네이션을 추가했다 — 자원 낭비를 최소화하기 위해 이미 첫 페이지에서
> 다 채워지는 언론사(대부분)는 종전과 동일하게 1번만 호출된다.

> **검색어 다중화 (2026-08-24 추가)**: 페이지네이션 이후에도 디일렉·조선비즈·이코노미스트
> 3곳은 여전히 0건이었다. 원인은 "증시"라는 단일 검색어 자체의 한계였다 — 조선비즈는 "증시"로는
> 0건이지만 "코스피"로 검색하면 "[마켓뷰] 삼성전자 급락에 코스피 6700선 아래로" 같은 진짜
> 시황 기사가 나왔다(검색어별로 네이버가 돌려주는 후보 집합 자체가 달라짐, 단순히 더 많이
> 가져온다고 해결되는 문제가 아니었음). `GENERAL_MARKET_QUERY`(String 1개)를
> `GENERAL_MARKET_QUERIES`(List, "증시"→"코스피"→"주가"→"코스닥" 순서로 시도)로 바꿨다 — 앞
> 검색어에서 이미 `MAX_RESULTS`를 채우면 뒤 검색어는 호출하지 않는 early-exit 유지, 검색어를
> 넘나들며 같은 기사가 중복 잡히는 경우는 link 기준으로 제거. 이 수정으로 조선비즈·이코노미스트
> 둘 다 실제 시황 기사가 잡혀 살아났다.

> **제목 기준 중복 제거 (2026-08-24 추가)**: 통신사(연합뉴스 등)가 같은 기사를 시간대별로
> 갱신 재배포하면 link는 다른데 제목은 완전히 동일한 경우가 실측 확인됐다("삼전·닉스 동반
> 하락에 코스피 3% 하락"이 5건 중 4건). link 기준 중복 제거(`seenLinks`)만으로는 못 잡아서
> 제목 기준(`seenTitles`)도 추가했다 — 중복으로 걸러진 기사는 `MAX_RESULTS` 카운트에도 안
> 잡히므로, early-exit 조건(`collected.size() < MAX_RESULTS`)이 자연스럽게 다른 검색어/
> 페이지를 더 뒤져 진짜 다른 기사로 채운다(중복만 빼고 5건 미만으로 짧아지는 게 아니라,
> 빠진 자리를 다른 기사로 다시 채움).

> **`UNRELIABLE_BRIEFING_OUTLET_DOMAINS` (2026-08-24 추가, 검색어 다중화 후 축소)**: 검색어를
> 4개까지 넓혀도(2000건 넘게 스캔) 디일렉(`thelec.kr`, 반도체 장비 전문지)만 끝까지 시황 관련
> 기사가 0건이었다 — 이 언론사는 애초에 "오늘의 시황"을 다루지 않는 것으로 판단해 유일하게
> `getSelectableOutlets()`/`validateOutlet()`에서 제외한다. 필터를 억지로 느슨하게 풀면 이전에
> 고친 "본문에 살짝 스친 무관 기사 통과" 문제가 재발하므로, 대신 애초에 "기사 없음"이 나올
> 선택지 자체를 없앴다(재무설계사가 쓰는 `NewsRelevanceMatcher.OUTLET_NAMES`/
> `SECURITIES_NEWS_DOMAINS` 원본은 건드리지 않음 — 이 기능 전용 28곳 선택지만 별도로 좁힌 것).

> **Gemini 요약 호출**: 대화 이력(`history`)도, 도구(`tools`)도 없이 `systemInstruction` +
> `prompt`만 채운 1회성 `GeminiRequest`를 `GeminiRequest.GeminiModel.ANSWER`로 호출한다.
> `RedisRateLimiterService`(분당3/일일10)는 적용하지 않는다 — 그 한도는 사용자가 직접 채팅을
> 남용하는 것을 막기 위한 것이라, 서버가 스스로 도는 배치 작업의 목적과 다르다.

> **근거 검증/재생성 (2026-08-24 1차 시도 후 보류 → 같은 날 재도입, 라이브 재검증 완료)**:
> Gemini가 원본 기사에 없는 수치·회사·사건을 지어내는 문제(코스피 지수 오기재, "SK하이닉스
> 12% 급등" 등 완전 지어내기, 시점 혼합 — 총 3종)를 막기 위해 2단계로 대응한다.
> **1단계**: `SUMMARY_SYSTEM_INSTRUCTION`에 "기사 원문에 없는 정확한 소수점 숫자는 쓰지 말고
> '6,700선'처럼 대략적으로만 표현하라"는 지시를 추가해 지수 숫자 오기재 자체의 발생 빈도를
> 줄인다. **2단계**: 요약 생성 직후 `isGrounded()`가 별도 `GeminiRequest.GeminiModel.JUDGE`
> 호출로 "원본 기사 목록 대비 브리핑 초안에 근거 없는 문장이 있는지"를 판정("SAFE" 또는
> "UNSAFE: <인용>")한다. UNSAFE면 `generateVerifiedSummary()`가 `MAX_SUMMARY_ATTEMPTS`(2)
> 안에서 폐기 후 재생성하고, 마지막 시도까지 UNSAFE면 **지어낸 내용을 사용자에게 보내느니
> 이번 브리핑 자체를 포기한다**(null 반환 → `generateBriefingForUser()`가 저장·알림 없이
> 스킵, 다음날 스케줄에서 다시 시도) — 증권 정보는 틀린 걸 보내는 것보다 아예 안 보내는 게
> 낫다는 판단(2026-08-24 확정, 처음엔 "재시도 소진 시 그대로 발송"으로 짰다가 라이브 테스트
> 중 사용자 지적으로 즉시 "폐기"로 정정).
> **라이브 재검증(아주경제, SK하이닉스 지어내기가 3회 재현됐던 데이터셋) 결과**: 1·2차 생성
> 모두 검증에서 UNSAFE 판정을 받았고("6,700선" 등 지수 숫자 오기재를 정확히 짚어냄), 최종적으로
> 브리핑을 발송하지 않고 스킵 처리됨을 확인(`checkRealBriefing()`에서 `생성 여부 === false`).

> **비서 톤 인사말 (2026-08-24 사용자 요청)**: 브리핑 본문 맨 앞에 "안녕하세요, AI 시황 비서
> AI STOCK입니다. 오늘의 주요 시황을 브리핑해드리겠습니다."를 항상 붙인다. `AiPlanningService`의
> 첫 인사 버그(프롬프트로만 시키면 가끔 누락됨, 8-9 참고)와 같은 이유로 Gemini에게 맡기지
> 않고 `AiNewsService.BRIEFING_GREETING` 상수를 코드가 직접 앞에 붙인다 — `SUMMARY_SYSTEM_INSTRUCTION`은
> 반대로 "인사말은 쓰지 마라"고 명시해 중복을 막는다.

> **요약 근거 기사 링크 (2026-08-24 사용자 요청)**: "요약이 진짜 근거가 있는지 원문으로 확인하고
> 싶다"는 요청으로, `NaverNewsApiClient.searchByOutlet()` 결과(title/link/outlet)를
> `NewsSourceLinkDto` 리스트로 만들어 `news_briefings.source_links`(JSON)에 함께 저장한다.
> `GET /api/ai/news/briefings/today` 응답의 `sources` 필드로 그대로 노출되며, 프론트는 각 기사의
> `link`로 원문 이동 버튼/링크를 만들면 된다.

### 8-20. feature/admin-api-p0 (2026-09-07 신규 — 관리자 알림 발송)

| 구분 | 이름 |
|---|---|
| Controller | `AdminNotificationController` |
| 엔드포인트 | `POST /api/admin/notifications/users/{userId}`, `POST /api/admin/notifications/broadcast` |
| Service | `AdminNotificationService` — `notifyUser(Long userId, AdminNotificationRequest request)`, `broadcast(AdminNotificationRequest request)` |
| Request DTO | `AdminNotificationRequest`(title, content, type: `NotificationType`) |

> admin 도메인은 자체 저장 로직 없이 `NotificationService.notify()`(8-12)를 그대로 재사용한다.
> `broadcast()`는 `UserRepository.findAllActiveUserIds()`로 뽑은 활성 유저 전원에게 순차
> 반복 호출한다(대량 insert/비동기 처리는 이번 범위에서 다루지 않음 — 유저 규모가 커지면
> 전환 검토).

### 8-21. feature/admin-api-p0 (2026-09-07 신규 — 비밀번호 확인·변경)

| 구분 | 이름 |
|---|---|
| Controller | `UserController`(기존 컨트롤러에 추가) |
| 엔드포인트 | `POST /api/users/me/password/verify`, `PATCH /api/users/me/password` |
| Service | `UserService` — `verifyPassword(Long userId, PasswordVerifyRequest request)`, `changePassword(Long userId, PasswordChangeRequest request)` |
| Request DTO | `PasswordVerifyRequest`(password), `PasswordChangeRequest`(currentPassword, newPassword — `SignupRequest.password`와 동일한 `@Pattern`/`@MaxByteSize` 정책) |
| Entity 메서드 | `User.changePassword(String encodedPassword)` |
| ErrorCode | `PASSWORD_NOT_SET`(401) — 소셜 로그인 전용 계정(password=null)이 확인/변경을 시도한 경우 |

> `changePassword()` 성공 시 `RedisTokenService.deleteRefreshToken()`으로 기존 Refresh Token을
> 폐기한다(handoff 문서 5.3 요구사항). Access Token 블랙리스트 등록까지는 하지 않는다 — 이
> 메서드는 Access Token 문자열을 받지 않으므로(요청 헤더 필요) 다음 재발급 시점에 자연히 막힌다.

### 8-22. feature/admin-api-p0 (2026-09-07 신규 — 관리자 계정 관리)

| 구분 | 이름 |
|---|---|
| Controller | `AdminAdminController` |
| 엔드포인트 | `GET /api/admin/admins`, `GET /api/admin/admins/{adminId}`, `PATCH /api/admin/admins/{adminId}/status` |
| Service | `AdminUserService`(8-17)에 `getAdminDetail(Long adminId)`, `updateAdminStatus(Long adminUserId, Long adminId, AdminUserStatusRequest request)` 추가 — 목록은 기존 `getUsers(role=ADMIN 고정)` 재사용 |

> **`updateAdminStatus()` 전용 메서드 추가 (코드리뷰 반영)**: 처음엔 `AdminAdminController`가
> `updateUserStatus()`를 그대로 호출했는데, 그러면 `PATCH /api/admin/admins/{adminId}/status`에
> ADMIN이 아닌 일반 userId를 넣어도 상태가 바뀌어버려 "관리자 전용" URL의 의미가 깨졌다.
> `updateAdminStatus()`가 대상이 `Role.ADMIN`인지 먼저 확인하고 아니면 `USER_NOT_FOUND`를 던진
> 뒤 내부적으로 `updateUserStatus()`에 위임한다(`getAdminDetail()`이 role 체크로 URL 의미를
> 지키는 것과 동일한 패턴).

> 관리자도 `users` 테이블의 `User(role=ADMIN)`일 뿐이라 별도 Entity 없이 `AdminUserService`의
> 검색·상태변경 로직을 role 고정 조건으로 재사용한다 — 자기 자신 정지 금지, 마지막 활성
> 관리자 정지 금지 가드(`validateSuspendable()`)도 동일하게 적용된다. `getAdminDetail()`은
> `getUserDetail()`과 조회 로직은 같지만 role이 ADMIN이 아니면 `USER_NOT_FOUND`로 막아
> "관리자 전용 목록에서 조회"라는 URL 의미를 지킨다.
>
> **`POST /api/admin/admins` 추가 (2026-09-07, handoff 문서 9번 "구현 전 결정이 필요한 정책"
> 6번 — 정책 확정 전 "별도 생성 방식"으로 우선 구현하기로 함)**: `AdminUserService.
> createAdmin(AdminCreateRequest request)`(loginId, password, name, email) — 승격 방식이
> 아니라 신규 User(role=ADMIN)를 직접 만든다. `AuthService.signup()`과 동일한 패턴으로
> 중복 아이디/이메일을 먼저 걸러내고, `save()` 시점 동시 가입 경합은 `DataIntegrityViolationException`을
> 잡아 재조회 후 `DUPLICATE_LOGIN_ID`/`DUPLICATE_EMAIL`로 변환한다. **승격 방식으로 정책이
> 정해지면 이 메서드 자체를 제거**하고 `PATCH /api/admin/users/{userId}/status`류로 역할만
> 바꾸는 방향으로 대체해야 한다.

### 8-23. feature/admin-api-p0 (2026-09-07 신규 — 기간별 통계)

| 구분 | 이름 |
|---|---|
| Controller | `AdminStatisticsController` |
| 엔드포인트 | `GET /api/admin/statistics/users`, `GET /api/admin/statistics/orders`, `GET /api/admin/statistics/amounts` (공통 쿼리 파라미터: `from`, `to`(날짜, ISO DATE), `interval`(`DAY`/`WEEK`/`MONTH`, 기본값 `DAY`)) |
| Service | `AdminStatisticsService` — `getUserStatistics()`, `getOrderStatistics()`, `getAmountStatistics()`(전부 `LocalDateTime from, LocalDateTime to, StatisticsInterval interval`) |
| 내부 타입 | `StatisticsInterval`(enum, MySQL `DATE_FORMAT` 패턴 보유 — DAY=`%Y-%m-%d`, WEEK=`%x-%v`, MONTH=`%Y-%m`) |
| Response DTO | `StatisticsPointResponse`(period, value) ← `StatisticsPointProjection`(period, value, `global/util` 소속) 네이티브 쿼리 프로젝션 |
| Repository | `UserRepository.aggregateUserSignups()`, `OrderRepository.aggregateOrderCounts()`, `OrderRepository.aggregateExecutedAmounts()`(전부 `nativeQuery = true`) |

> JPQL은 MySQL 전용 함수(`date_format`)를 못 써서 네이티브 쿼리로 작성했다. `pattern`(포맷
> 문자열)도 일반 바인드 파라미터로 넘긴다 — 컨트롤러가 `StatisticsInterval` enum으로만 받아
> 세 값 중 하나만 전달되므로 SQL 인젝션 우려는 없다. WEEK는 `%x-%v`(ISO 8601 연도-주차)를
> 써서 연말/연초 경계에서 실제 주 단위와 어긋나지 않게 했다.

> **`StatisticsPointProjection` 위치 이동 (코드리뷰 반영)**: 처음엔 `domain/admin/dto/projection`
> 소속이었는데, `OrderRepository`/`UserRepository`(order/user 도메인)가 이 인터페이스를
> 반환 타입으로 쓰려면 admin 도메인을 역참조해야 해서 8-27에서 정리한 것과 같은 순환 의존
> 문제가 생겼다. 도메인 어디에도 속하지 않는 순수 반환 타입 형태라 `global/util`로 옮기고
> 원래 디렉터리(빈 채로 남은 `domain/admin/dto/projection`)는 삭제했다 — `OrderRepository`,
> `UserRepository`, `StatisticsPointResponse`의 import를 전부 새 위치로 바꿨다.

### 8-24. feature/admin-api-p0 (2026-09-07 신규 — CSV 내보내기)

| 구분 | 이름 |
|---|---|
| 엔드포인트 | `GET /api/admin/users/export`, `GET /api/admin/trades/export`(handoff 문서는 `/api/admin/orders/export`를 제안했으나 기존 목록 API가 이미 `/api/admin/trades`라 같은 경로 아래 통일) |
| Controller 메서드 | `AdminUserController.exportUsers()`, `AdminTradeController.exportTrades()` — 둘 다 `ResponseEntity<byte[]>` 반환(파일 다운로드라 `ApiResponse<T>` JSON 포맷 예외) |
| Service 메서드 | `AdminUserService.exportUsersCsv(String query, UserStatus status, Role role, Sort sort)`, `AdminTradeService.exportTradesCsv(String query, OrderStatus status, OrderType orderType, PriceType priceType, String stockCode, LocalDateTime from, LocalDateTime to, Sort sort)` |
| 공용 유틸 | `global/util/CsvWriter`(신규) — `write(List<String> headers, List<List<String>> rows)`, UTF-8 BOM 부착, RFC 4180 이스케이프 |

> 화면과 동일한 검색·필터 조건을 그대로 받아 기존 `searchUsers()`/`searchOrdersWithUser()`에
> 넘기되, 전역 설정(`application.yml`의 `spring.data.web.pageable.max-page-size=100`)에
> 걸리지 않도록 서비스 내부에서 직접 `PageRequest.of(0, Integer.MAX_VALUE, sort)`를 만든다 —
> 그 설정은 HTTP 쿼리 파라미터(`size=...`)를 해석할 때만 적용되고 코드에서 만든 `PageRequest`엔
> 적용되지 않는다. 충전요청·감사로그 CSV(`/api/admin/charge-requests/export`,
> `/api/admin/audit-logs/export`)는 2026-09-07 이후 두 도메인 자체는 생겼지만(8-25, 8-27)
> export 엔드포인트까지는 이번 범위에서 만들지 않았다 — 필요해지면 이 두 서비스에
> exportXxxCsv() 메서드만 추가하면 된다(패턴은 이미 있음).

> **CSV 수식 인젝션(Formula/DDE Injection) 방어 (코드리뷰 반영)**: `CsvWriter.escape()`가
> 셀 값을 RFC 4180 규칙(쉼표/줄바꿈/따옴표 포함 시 큰따옴표로 감싸기)으로만 이스케이프했는데,
> 엑셀·구글시트는 셀 값이 `=`/`+`/`-`/`@`로 시작하면 그 내용을 수식으로 실행한다. 사용자가
> 이름·문의 제목 등에 `=CMD(...)` 같은 문자열을 넣어두면 관리자가 CSV를 엑셀로 열 때 의도치
> 않은 코드가 실행될 수 있는 OWASP에 알려진 취약점이다. `FORMULA_TRIGGER_CHARS = "=+-@"` 상수를
> 추가해 값이 이 문자로 시작하면 RFC 4180 이스케이프 전에 앞에 작은따옴표(`'`)를 붙여 엑셀이
> 문자열로만 취급하게 만든다(`CsvWriterTest`에 BOM/RFC4180/수식트리거 케이스 7개 테스트 추가).

### 8-25. feature/admin-api-p0 (2026-09-07 신규 — 충전 요청·승인, 새 테이블)

`charge_requests` 테이블 신규 생성(사용자 승인, ADMIN_API_BACKEND_HANDOFF.md 4.2 — 자동 충전
3회 한도를 넘긴 사용자가 관리자 승인을 받는 절차).

| 구분 | 이름 |
|---|---|
| Entity | `ChargeRequest`(domain/account/entity) — requestId, account, amount, reason, status(`ChargeRequestStatus`: PENDING/APPROVED/REJECTED), decidedBy(User, nullable), decisionReason, requestedAt, decidedAt |
| Repository | `ChargeRequestRepository`(domain/account/repository) — `findAllByAccountId`, `findByRequestIdAndAccountId`, `existsByAccount_AccountIdAndStatus`, `searchWithAccountAndUser`, `findWithAccountAndUserById`, `findByIdForUpdate`(비관적 락) |
| 사용자 엔드포인트 | `POST /api/accounts/{accountId}/charge-requests`, `GET /api/accounts/{accountId}/charge-requests`, `GET /api/accounts/{accountId}/charge-requests/{requestId}` (AccountController에 추가) |
| 사용자 Service | `ChargeRequestService`(domain/account/service) — `createRequest`, `getMyRequests`, `getMyRequestDetail` |
| 관리자 엔드포인트 | `GET /api/admin/charge-requests`, `GET /api/admin/charge-requests/{requestId}`, `PATCH /api/admin/charge-requests/{requestId}/decision` |
| 관리자 Controller/Service | `AdminChargeRequestController`, `AdminChargeRequestService` — `getRequests`, `getRequestDetail`, `decide(Long adminUserId, Long requestId, AdminChargeDecisionRequest request)` |
| Request DTO | `ChargeRequestCreateRequest`(amount, reason), `AdminChargeDecisionRequest`(decision: `ChargeRequestStatus`, reason) |
| Response DTO | `ChargeRequestResponse`(사용자용), `AdminChargeRequestResponse`(관리자용 — 요청자/처리자 식별 정보 포함) |
| Account 엔티티 메서드 | `applyAdminCharge(long amount)` — `chargeBalance()`와 달리 `chargeCount`를 안 올린다 |
| ErrorCode | `CHARGE_REQUEST_NOT_FOUND`(404), `CHARGE_REQUEST_ALREADY_PENDING`(400 — 계좌당 PENDING 1건 제한, 정책 미확정 상태에서 우선 구현), `CHARGE_REQUEST_ALREADY_PROCESSED`(409) |

> 계좌별 미처리 요청 중복 생성 방지는 handoff 문서가 "정책 필요"로 남긴 항목이라, 우선
> "동시에 PENDING 1건만 허용"으로 구현했다. 승인·거절 금액의 일·월 누적 한도 검증도 문서가
> 요구하지만 구체적 허용 범위가 미확정이라 넣지 않았다 — 정해지면 `AdminChargeRequestService.
> decide()`에 검증을 추가해야 한다.

> **`createRequest()` 락·정지 계좌 가드 (코드리뷰 반영)**: 원래 `AccountService.
> getOwnedAccount()`(락 없음)로 계좌를 조회한 뒤 PENDING 존재 여부를 확인했는데, 동시에 두
> 요청이 들어오면 둘 다 "PENDING 없음"을 보고 통과해 계좌당 1건 제한이 깨질 수 있었다.
> `AccountService`에 `getOwnedAccountForUpdate(Long userId, Long accountId)`(비관적 락,
> `findByAccountIdAndUserIdForUpdate` 재사용)를 추가해 `createRequest()`가 이걸로 바꿔
> 잠근 상태에서 PENDING 존재 여부를 확인한다. 같은 메서드에서 계좌 `status`가 `SUSPENDED`면
> `ACCOUNT_SUSPENDED`를 던져 정지 계좌가 충전 요청 자체를 못 넣게 막는다(CLAUDE.md 8번 "정지된
> 계좌는 매수·매도만 차단, 조회는 가능" 원칙에 맞춰 충전 요청도 매수·매도에 준하는 자금 이동
> 행위로 취급). 조회 전용 메서드(`getMyRequests`/`getMyRequestDetail`)는 그대로 락 없는
> `getOwnedAccount()`를 쓴다.

### 8-26. feature/admin-api-p0 (2026-09-07 신규 — 잔고 변동 원장, 새 테이블)

`account_transactions` 테이블 신규 생성(사용자 승인, ADMIN_API_BACKEND_HANDOFF.md 4.3).

| 구분 | 이름 |
|---|---|
| Entity | `AccountTransaction`(domain/account/entity) — append-only(수정 메서드 없음). transactionId, account, type(`AccountTransactionType`: INITIAL_GRANT/AUTO_CHARGE/ADMIN_CHARGE/ADMIN_DEDUCTION/ORDER_BUY/ORDER_SELL/ORDER_REFUND), amount(부호 있는 증감액), balanceBefore, balanceAfter, relatedOrderId/relatedChargeRequestId/processedBy(전부 FK 아닌 단순 참조 ID), reason, createdAt |
| Repository | `AccountTransactionRepository`(domain/account/repository) — `findAllByAccount_AccountId` |
| Service | `AccountTransactionService`(domain/account/service) — `record(Account account, AccountTransactionType type, long amount, long balanceBefore, Long relatedOrderId, Long relatedChargeRequestId, Long processedBy, String reason)`, `getTransactions(Long accountId, Pageable pageable)` |
| 사용자 엔드포인트 | `GET /api/accounts/{accountId}/transactions` (AccountController에 추가) |
| 관리자 엔드포인트 | `GET /api/admin/accounts/{accountId}/transactions`, `POST /api/admin/accounts/{accountId}/adjustments` (AdminAccountController에 추가) |
| Request DTO | `AdminAccountAdjustmentRequest`(type: ADMIN_CHARGE/ADMIN_DEDUCTION만 허용, amount, reason) |
| Response DTO | `AccountTransactionResponse` |
| Account 엔티티 메서드 | `applyAdminDeduction(long amount)` — `applyAdminCharge()`와 대칭 |

> **record() 호출 지점(잔고를 바꾸는 모든 곳에 연결 완료)**:
> - `AccountService.createAccount()` → INITIAL_GRANT
> - `AccountService.chargeBalance()` → AUTO_CHARGE
> - `OrderService.executeBuy()/executeSell()`(시장가) → ORDER_BUY/ORDER_SELL
> - `OrderService.createLimitOrder()`(지정가 매수, `freezeForOrder` 시점) → ORDER_BUY
> - `OrderExecutionService.executeBuy()`(지정가 매수 체결 시 차액 환급, `refundAmount > 0`일 때만) → ORDER_REFUND
> - `OrderExecutionService.executeSell()`(지정가 매도 체결) → ORDER_SELL
> - `OrderService.cancelOrder()`/`adminCancelOrder()`/`cancelAllPendingOrdersForSuspension()`(매수 주문 취소·환불) → ORDER_REFUND
> - `AdminChargeRequestService.decide()`(APPROVED) → ADMIN_CHARGE
> - `AdminAccountService.adjustBalance()` → ADMIN_CHARGE 또는 ADMIN_DEDUCTION
>
> `balanceBefore`는 호출부가 Account 엔티티의 잔고 변경 메서드를 부르기 "직전" 값을 직접
> 읽어서 넘긴다(`AccountTransactionService.record()` Javadoc 참고) — `account.getBalance()`는
> 이미 변경된 이후 값이라 그 시점엔 balanceBefore를 알 수 없다.
> `OrderService`/`OrderExecutionService`가 `AccountTransactionService`(account 도메인)를
> 호출하는 방향은 `CLAUDE.md` 4번 "도메인 간 직접 참조 대신 서비스 계층을 통해 호출"에 부합한다.

> **`adjustBalance()` 락·잔고검증·감사로그 보강 (코드리뷰 반영)**: `AccountRepository`에
> `findAccountWithUserByIdForUpdate(accountId)`(비관적 락 + `user` fetch join)를 추가하고
> `adjustBalance()`가 락 없는 조회 대신 이걸 쓰도록 바꿨다 — 동시에 여러 조정 요청이 들어와도
> 순차적으로만 반영되게 하기 위함(다른 계좌 잔고 변경 지점들과 동일하게 비관적 락 원칙 통일).
> ADMIN_DEDUCTION 처리 시 `account.getBalance() < amount`뿐 아니라 `account.getBaseBalance()
> < amount`도 함께 확인해 차감 후 `baseBalance`가 음수가 되는 경우까지 `INSUFFICIENT_BALANCE`로
> 막는다(`baseBalance`는 수익률 계산 기준값이라 음수 허용 시 수익률 계산 자체가 깨짐). 조정 성공
> 시 `AuditLogService.record(adminUserId, ACTION_ACCOUNT_ADJUSTMENT, TARGET_ACCOUNT, accountId,
> beforeBalance, afterBalance, reason)`을 호출해 8-27의 감사 로그에도 남긴다.

### 8-27. feature/admin-api-p0 (2026-09-07 신규 — 관리자 작업 감사 로그, 새 테이블)

`audit_logs` 테이블 신규 생성(사용자 승인, ADMIN_API_BACKEND_HANDOFF.md 5.2).

| 구분 | 이름 |
|---|---|
| Entity | `AuditLog`(domain/admin/entity) — append-only(수정 메서드 없음). auditLogId, adminUserId, adminLoginId(기록 시점 스냅샷), action, targetType, targetId, beforeValue, afterValue, reason, requestIp(코드리뷰 반영 이후 실제로 채워짐 — 아래 참고), createdAt |
| Repository | `AuditLogRepository`(domain/admin/repository) — `search(action, adminId, targetType, targetId, from, to, pageable)` |
| Service | `AuditLogService`(domain/admin/service) — `record(Long adminUserId, String action, String targetType, Long targetId, String beforeValue, String afterValue, String reason)`, `search(...)`, `getDetail(Long auditLogId)`. action/targetType 문자열 상수(`ACTION_*`/`TARGET_*`, `ACTION_ACCOUNT_ADJUSTMENT` 코드리뷰 반영으로 추가)를 이 클래스에 모아둔다 |
| 엔드포인트 | `GET /api/admin/audit-logs`, `GET /api/admin/audit-logs/{auditLogId}` (수정·삭제 API 없음 — handoff 문서 "관리자도 수정·삭제할 수 없게 한다") |
| Controller | `AdminAuditLogController` |
| Response DTO | `AuditLogResponse` |
| ErrorCode | `AUDIT_LOG_NOT_FOUND`(404) |

> **admin 도메인 원칙 예외**: CLAUDE.md 4번/NAMING.md 8-14~8-18은 "admin 도메인은 자체
> Entity/Repository를 두지 않는다"는 원칙을 명시하지만, 감사 로그는 다른 도메인에 자연스러운
> 소속처가 없는 admin 고유 데이터라 이번만 예외로 admin 도메인이 직접 Entity/Repository를
> 갖는다(AuditLog 클래스 Javadoc 참고).
>
> **record() 호출 지점**: `AdminUserService.updateUserStatus()`(USER_STATUS_CHANGE),
> `AdminUserService.createAdmin()`(ADMIN_CREATE), `AdminAccountService.updateAccountStatus()`
> (ACCOUNT_STATUS_CHANGE — 이때문에 `AdminAccountStatusRequest`에 `reason` 필드를 추가함,
> 최초 구현(8-16)엔 없었음), `AdminAccountService.adjustBalance()`(ACCOUNT_ADJUSTMENT, 코드리뷰
> 반영으로 추가 — 8-26 참고), `OrderService.adminCancelOrder()`(ORDER_CANCEL, 아래 이벤트 방식
> 참고), `AdminChargeRequestService.decide()`(CHARGE_REQUEST_DECISION).
>
> **`AdminOrderCancelledEvent`/`AdminAuditEventListener`로 순환 의존 해소 (코드리뷰 반영)**:
> 원래 `OrderService.adminCancelOrder()`가 admin 도메인의 `AuditLogService`를 직접 주입받아
> 호출했는데, admin 도메인이 이미 order 도메인의 `OrderRepository`를 참조하고 있어(관리자
> 거래관리 조회용) 두 도메인이 서로를 참조하는 순환 의존이 생겼다(`OrderRepository.java`,
> `OrderService.java`, `UserRepository.java`에서 admin→order/user, order→admin 양방향 import가
> 실제로 확인됨). `domain/order/event`에 `AdminOrderCancelledEvent`(record: adminUserId,
> orderId, reason)를 새로 만들고, `OrderService`는 `AuditLogService` 의존을 없앤 뒤
> `ApplicationEventPublisher.publishEvent(new AdminOrderCancelledEvent(...))`만 발행한다.
> `domain/admin/service`의 `AdminAuditEventListener`(신규)가 `@EventListener`로 이 이벤트를
> 받아 `AuditLogService.record(...)`를 호출한다 — order→admin 직접 호출을 스프링 이벤트
> 발행/구독으로 뒤집어 컴파일 시점 순환 의존을 없앴다(`domain/notification`의
> `NotificationService`를 여러 도메인이 직접 호출하는 기존 패턴과 달리, 이 경우는 "admin이
> 다른 도메인의 동작을 감사"하는 역방향이라 이벤트가 더 적합하다고 판단).
>
> **`requestIp` 실제 채움 (코드리뷰 반영)**: 처음엔 "서비스 메서드 시그니처에 IP를 추가로
> 넘겨야 해서 범위상 보류"였으나, `JwtAuthenticationFilter.authenticate()`가 인증 성공 시
> `authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request))`로
> `WebAuthenticationDetails`(요청 IP 포함)를 `SecurityContext`에 심어두도록 바꿔서 서비스
> 메서드 시그니처를 하나도 안 건드리고 해결했다. `AuditLogService`의 새 private
> `currentRequestIp()`가 `SecurityContextHolder.getContext().getAuthentication().getDetails()`를
> `WebAuthenticationDetails`로 캐스팅해 `getRemoteAddress()`를 꺼내 `record()`가 빌드하는
> `AuditLog.requestIp`에 채운다. 인증 컨텍스트가 없는 경로(예: 통합 테스트에서 직접 서비스 호출)는
> `getDetails()`가 null이라 여전히 requestIp가 null로 남는다.

---

## 9. 공통 변수명 컨벤션 (모든 도메인 공통 적용)

| 상황 | 변수명 |
|---|---|
| 현재 로그인한 사용자 ID | `userId` (SecurityUtil.getCurrentUserId() 반환값) |
| 경로 변수 | `{stockCode}`, `{orderId}`, `{sessionId}`, `{simulationId}`, `{notiId}`, `{inquiryId}`, `{accountId}` — Controller 파라미터명도 동일하게 맞춤 |
| 페이지네이션 사용 시 | `page`, `size`, `sort` (쿼리 파라미터), 반환은 `Page<T>` 또는 `List<T>` 중 도메인별 통일 필요 시 별도 협의. 관리자 목록 API(`admin-trade`, `admin-user`, `admin-inquiry`)는 데이터量이 많아질 수 있어 `Page<T>`로 통일한다. |
| 목록 반환 변수 | 복수형 (`orders`, `holdings`, `notifications`) |

> **페이지 size 상한 (코드리뷰 반영, v8)**: 관리자 목록 API가 전부 `@PageableDefault(size = 20)`만
> 걸어뒀는데, 이는 요청에 `size`가 없을 때의 기본값일 뿐 상한이 아니라 `?size=999999999` 같은
> 요청이 그대로 통과해 전체 테이블을 한 번에 긁어올 수 있었다. 컨트롤러마다 검증을 반복하는
> 대신 `application.yml`의 `spring.data.web.pageable.max-page-size: 100`으로 전역 상한을 건다
> (Spring Data Web `PageableHandlerMethodArgumentResolver`가 요청 `size`를 이 값으로 clamp).
# 프론트 기능 완성 API (2026-09-09)

- `RealizedReturnService.getReturns/calculateReturns`, `RealizedReturnController`, `RealizedReturnResponse(orderId, stockCode, stockName, quantity, averageCost, sellPrice, profitAmount, profitRate, executedAt)`: GET `/api/accounts/{accountId}/returns`. 체결 순서대로 매수 평균단가(원 단위 내림)를 재현하여 매도 실현손익 계산. 수수료·배당·이자는 현재 모의주문 원장에 없어 계산에 포함하지 않는다.

- `RedisAuthCodeService.VERIFY_EMAIL_SCRIPT`: 인증 코드 검증·실패횟수 제한·원자적 소비. 신규 API 복구 요청의 인증코드 재사용 및 무제한 추측 방지.
- `SignupRequest.investmentLevel`: 가입 화면에서 선택한 투자 경험을 기존 투자 프로필에 저장.

- `UserWithdrawalService.withdraw`: DELETE `/api/users/me`, `PasswordVerifyRequest`로 현재 비밀번호 확인. 계좌 잠금, 자식 데이터 삭제, 개인정보 익명화, Redis 주문/인증 정리. 마지막 활성 관리자 탈퇴 방지.

- `MarketQueryService.getIndexes`, `getResearch`: GET `/api/market/indexes`, GET `/api/market/stocks/{stockCode}/research?section=finance|earnings|dividend|peers|analysts`. DART/LS 실데이터로 조회, 자료가 없으면 빈 목록 또는 명시적 오류 응답.

- `PlanningPreferences` / Repository / Service / Controller, `PlanningPreferencesRequest(savedBriefingDates, linkedBriefingDates, linkedGoalPlanIds)`: GET/PUT `/api/ai/planning/preferences`. 본인 브리핑 저장과 AI 자료 연동 설정. `getPreferences`, `savePreferences`, `describeConnections`, `updateSelections`. 사용자 소유권 검증, 목록 개수 제한, 낙관적 잠금 적용.

- `MarketQueryController`, `MarketQueryService`: GET `/api/market/rankings?sort=volume|value|change|market-cap`, GET `/api/market/stocks/{stockCode}/history?months=12`, GET `/api/market/stocks/{stockCode}/detail`, GET `/api/market/news?query=`. 기존 LS/네이버 클라이언트 재사용. `getRankings`, `getHistory`, `getDetail`, `getNews`.
- `AiNewsService.getBriefingHistory`, `getBriefing`, `NewsBriefingRepository.findTop100ByUserUserIdOrderByBriefingDateDesc`: GET `/api/ai/news/briefings`, GET `/api/ai/news/briefings/{date}`. 날짜별 본인 소유 브리핑만 조회.

- `GoalPlan`, `GoalPlanRepository`, `GoalPlanService`, `GoalPlanController`, `GoalPlanRequest(goal, monthlyPayment, years, annualReturn, aggressive)`, `GoalPlanResponse(planId, settings, futureValue, aggressiveFutureValue, saved, createdAt)`.
- `/api/goal-plans`: POST 계산·저장, GET 본인 목록, PATCH `/{planId}/saved` 북마크, DELETE `/{planId}` 삭제. 기존 단일종목 12개월 시뮬레이션과 분리한 장기 적립식 계산. `calculateFutureValue`, `createPlan`, `getPlans`, `savePlan`, `deletePlan`.

- `UserService.getInvestmentProfile`, `updateInvestmentProfile`, `InvestmentProfile.updatePreferences`, `InvestmentProfileUpdateRequest(investmentTendency, fundTendency, investmentLevel)`: GET/PUT `/api/users/me/investment-profile`.
- `UserInfoResponse.birthdate`, `UpdateUserRequest.birthdate`, `User.updateBirthdate`: 생년월일 조회/수정.

- `AuthController` / `AuthService`: `checkLoginId`, `findLoginId`, `resetPassword`.
- `LoginIdCheckResponse(loginId, available)`: GET `/api/auth/login-id/availability?loginId=`.
- `AccountRecoveryRequest(loginId, name, email, birthdate, code, newPassword)`: POST `/api/auth/find-id`, POST `/api/auth/password/reset`. 이메일 인증코드를 직접 소비하며 계정 정보 일치를 확인한다.
- `findLoginId` 응답은 인증된 본인의 아이디 문자열. `resetPassword`는 기존 Refresh Token을 폐기한다.
# 2026-09-09 UI 연동 추가 등록

- MarketQueryService.getExchangeRate / MarketQueryController.getExchangeRate: 원/달러 환율 실응답(LS t3521 R/USDKRWSMBS), 미설정 시 MARKET_NOT_CONFIGURED.

- OAuthProviderClient: authorizationUrl, getUserInfo — 제공자 실제 인증 URL/토큰/프로필 연동. OAuthAuthorizationController: authorize — 브라우저 세션에 state(10분) 보관, consumeState — 콜백 일회 검증.
- ProfileUpdateRequest / UserService.updateProfile: 개인정보·성향·선택적 비밀번호를 한 트랜잭션에서 저장.
- MarketQueryService.searchStock / MarketQueryController.searchStock: 종목명 또는 6자리 코드 검색.
