# ENHANCEMENT_PLAN_B.md — 팀원 B(김진우) 고도화 작업 계획

이 문서는 `backend_plan_14week` 계획서에서 **팀원 B(김진우)**가 담당한 브랜치
(`jwt-common`, `redis-config`, `security-config`, `redis-service-market`,
`order-market`, `order-limit`, `mypage-account`, `ai-planning`, `mypage-profit`,
`ai-news`)의 실제 구현 코드를 감사해서, 이제 필요한 **고도화(견고성·성능·비용관리·
엣지케이스)** 작업을 연관 기능 그룹별로 정리한 것이다.

- A(전우혁: 실시간 시세 파이프라인·시뮬레이션 계산엔진), C(전유찬: 로그인/OAuth·
  관리자 페이지) 담당 구역은 이 문서에서 다루지 않는다. 단, B 코드와 맞닿아 있어
  B 쪽에서만 손을 대도 해결이 안 되는 항목은 "연동 필요" 로 표시했다.
- 항목 순서는 심각도 순(치명적 버그 → 성능/비용 → 견고성 → 테스트 공백).
- 순서대로 다 할 필요는 없고, 고도화가 필요한 범위 전체를 참고용으로 모아둔 것이다.
- PR을 올릴 때는 기존 PR 제목 규칙(`[김진우] [FEAT] 기능명`)에 이미 병합됐던
  기능을 다시 고도화하는 경우 맨 뒤에 `version1`, `version2` ...를 붙인다.
  예: `[김진우] [FEAT] AI 재무설계 상담 도구 확장 및 LS/DART/네이버 연동 version1`

---

## 1. 주문·거래 (order-market / order-limit)

### 1-1. 지정가 주문이 영원히 체결되지 않음 — 치명적
- **현상**: `OrderExecutionService.checkAndExecute()`(재시도 로직 포함, 낙관적 락
  충돌 시 재시도)가 구현은 되어 있지만, 이 메서드를 호출하는 코드가 전체
  코드베이스에 없다. tick 수신처인 `StockBroadcastService`(A 담당)도
  `OrderExecutionService`를 참조하지 않는다.
- **영향**: `pending:orders`에 등록된 지정가 주문이 조건을 충족해도 체결되지 않는다.
  CLAUDE.md 8번 항목("tick 수신 시 pending:orders 확인 → 조건 충족 시 낙관적 락으로
  체결")이 미구현 상태.
- **해결 방향**: tick → `RedisPendingOrderService` 조회 → `OrderExecutionService.
  checkAndExecute()` 호출까지 이어지는 연결 지점을 만들어야 한다. tick 수신 자체는
  A의 `StockBroadcastService`가 담당이라 **A와 연동 필요** — B 쪽에서는
  `OrderExecutionService`에 tick 하나를 받아 처리하는 진입 메서드를 명확히 노출해두고,
  A 쪽 브로드캐스트 로직에서 그 메서드를 호출하도록 조율한다.
- 같은 이유로 `HoldingSettlementService`의 재시도 로직도 호출부가 없어 사실상
  죽은 코드다.

### 1-2. Holding 수량 방어 코드 부재
- **현상**: `Holding.decrease()`에 수량이 0 미만이 되는 것을 막는 방어 코드가 없다.
  현재는 호출부(OrderService)가 사전 검증하지만, Entity 자체에는 안전장치가 없다.
- **해결 방향**: `decrease()` 내부에 `if (quantity < 0) throw ...` 같은 불변식
  검증을 추가해 향후 호출부 실수로부터 방어한다.

---

## 2. redis-service-market (RedisStockCacheService / RedisPendingOrderService / RedisRateLimiterService)

### 2-1. RedisPendingOrderService의 선형 탐색
- **현상**: `getPendingOrders`/`removePendingOrder`가 종목별 대기 주문 리스트를
  매번 전체 range로 읽어 애플리케이션 레벨에서 JSON 파싱 후 선형 탐색한다.
- **영향**: 인기 종목에 대기 주문이 많이 쌓이면 tick마다 O(N) 반복이 발생한다.
  1-1이 해결돼서 실제로 tick마다 호출되기 시작하면 이 문제가 바로 드러난다.
- **해결 방향**: 주문 ID를 키로 하는 Redis Hash 구조로 바꾸거나, 가격대별로
  정렬된 자료구조(Sorted Set)를 검토해 탐색 비용을 줄인다. 1-1과 함께 처리하는 게
  효율적이다.

### 2-2. RedisRateLimiterService의 TOCTOU
- **현상**: `isAllowed()` → `increment()`가 원자적이지 않아, 동시 요청이 몰리면
  분당/일일 한도를 살짝 넘겨 카운트될 수 있다. `RedisOnlineStatusService`는 이미
  Lua 스크립트로 원자적 처리를 하고 있어 참고할 만한 패턴이 있다.
- **영향**: Gemini 호출 한도(분당 3회/일일 10회)가 근소하게 초과될 수 있다 —
  비용 관리 목적의 리미터라 완전히 무의미해지진 않지만 정확도가 떨어진다.
- **해결 방향**: `RedisOnlineStatusService`처럼 Lua 스크립트로 체크+증가를
  원자적으로 묶는다.

---

## 3. AI 상담·뉴스 (ai-planning / ai-news)

### 3-1. 대화 히스토리 요약 압축 미구현
- **현상**: `AiPlanningService`가 `MAX_HISTORY_MESSAGES = 60`으로 컨텍스트를
  자르기만 하고, 주석에 "60턴 넘으면 오래된 대화를 요약 압축하는 게 정석이지만
  아직 구현 안 함"이라고 명시돼 있다.
- **영향**: 60메시지를 넘긴 장기 상담에서 앞쪽 맥락(사용자가 이전에 말한 목표·
  상황)이 그냥 사라진다.
- **해결 방향**: 오래된 메시지 구간을 Gemini로 한 번 요약해서 시스템 메시지로
  치환하는 방식 검토. Session 하나 안에서 점진적으로 압축.

### 3-2. MAX_TOOL_CALL_ROUNDS=1 임시 축소 상태
- **현상**: 주석에 "Gemini 무료 등급 한계로 임시로 1로 축소, 유료 등급 전환 전까지의
  임시 조치"라고 명시돼 있다.
- **해결 방향**: 유료 전환 시점에 맞춰 라운드 수 복원 필요 — Gemini 실 사용
  전환은 비용이 드니 **사용자 승인 후 진행** (기존 합의 사항).

### 3-3. AiNewsService 일일 배치가 레이트리미터를 우회
- **현상**: `generateDailyBriefings()`가 매일 07시 전체 구독자를 순회하며 사용자당
  최대 4회(요약+검증 재시도 포함) Gemini를 호출하는데, 주석으로 명시하고
  `RedisRateLimiterService`를 의도적으로 건너뛴다.
- **영향**: 구독자 수가 늘어나면 배치 하나의 Gemini 비용이 상한 없이 선형 증가한다.
- **해결 방향**: 배치 레벨에서 별도의 일일 총 호출 상한(예: 배치 전체 N회 초과 시
  중단하고 관리자 알림)을 두거나, 구독자를 배치로 나눠 처리 속도/비용을 조절하는
  로직을 추가한다.

### 3-4. 오래된 세션/메시지 아카이빙 없음
- **현상**: 세션·메시지 삭제는 회원 탈퇴 시 `deleteByUserId` 한 경로만 있고,
  일정 기간 지난 세션을 정리하는 배치/TTL이 없다.
- **영향**: `ai_planning_sessions`/`ai_planning_messages` 테이블이 무한정 증가한다.
- **해결 방향**: 일정 기간(예: 6개월) 지난 `CLOSED` 세션을 아카이빙하거나 삭제하는
  스케줄러 검토. 우선순위는 낮음 — 데이터 늘어나는 속도를 보고 판단해도 된다.

### 3-5. 외부 API 에러가 뭉뚱그려짐
- **현상**: `ExternalApiInvoker.call()`이 `RestClientException`만 잡아 무조건
  `EXTERNAL_API_ERROR`(502)로 처리한다. 타임아웃/5xx/Gemini 자체 429(rate limit)를
  구분하지 않는다.
- **영향**: 사용자가 "우리 쪽 분당 3회 한도 초과"와 "Gemini 서버 자체 장애"를
  같은 메시지로 받는다.
- **해결 방향**: 예외 타입/HTTP 상태코드별로 `ErrorCode`를 분기하고, 재시도 가능한
  일시적 오류(타임아웃, 5xx)에 한해 짧은 재시도 1회 정도 검토.

---

## 4. 인증 기반 (jwt-common / security-config)

감사 결과 이 두 브랜치 자체(`JwtProvider`, `JwtAuthenticationFilter`,
`CustomUserDetailsService`, `SecurityConfig`)에서는 특별한 결함이 발견되지
않았다. `SecurityConfig`의 관리자 API 차단(`hasRole("ADMIN")`)도 정상 확인됨.
당장 급한 고도화 항목 없음 — 필요 시 JWT 만료/재발급 흐름 정도만 추가 점검하면
충분하다.

---

## 5. 테스트 커버리지 공백 (B 소유 파일 기준)

다음 파일들에 대한 단위 테스트가 `src/test`에 없다:
- `GeminiApiClient`
- `RedisRateLimiterService`
- `RedisAiToolCacheService`
- `AiNewsService`의 근거 검증 로직(`isGrounded()`)
- `OrderExecutionService`(1-1 해결 후에는 특히 필수 — 체결 로직 회귀 테스트 없이는
  또 조용히 죽은 코드가 될 위험)

우선순위는 `OrderExecutionService` > `RedisRateLimiterService`(비용 직결) >
나머지 순으로 보는 게 합리적이다.

---

## 참고 — 코드 수정 없이 조사만 완료된 시점의 스냅샷

이 문서는 2026-08-28 기준 dev 브랜치 코드 상태를 감사해 작성했다. 이후 코드가
바뀌어 항목이 해소되면 KNOWN_ISSUES.md와 동일한 원칙으로 이 문서에서도 해당
항목을 제거한다.
