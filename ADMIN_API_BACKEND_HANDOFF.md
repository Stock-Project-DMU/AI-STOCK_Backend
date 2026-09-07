# 관리자 API 백엔드 구현 요청서

- 작성일: 2026-08-29
- 대상 프로젝트: AI-STOCK
- 목적: 관리자 프론트엔드 완성과 운영 기능 구현에 필요한 백엔드 API 및 도메인 작업 정의

## 1. 현재 구현 상태

현재 관리자 전용 API는 아래 3개뿐이다.

| Method | Endpoint | 상태 | 설명 |
|---|---|---|---|
| `GET` | `/api/admin/users` | 구현됨 | 활성 회원 목록 페이징 조회 |
| `GET` | `/api/admin/users/{userId}` | 구현됨 | 회원 기본정보, 계좌, 보유 종목, 주문 내역 조회 |
| `PATCH` | `/api/admin/users/{userId}/status` | 구현됨 | 회원 `ACTIVE`/`SUSPENDED` 변경 |

모든 `/api/admin/**` 요청은 `ROLE_ADMIN`만 허용한다. 공통 응답은 기존 `ApiResponse<T>` 형식을 유지한다.

```json
{
  "success": true,
  "message": "성공",
  "data": {}
}
```

페이징 응답은 Spring `Page<T>` 직렬화 형식을 사용한다.

## 2. 우선순위

| 우선순위 | 구현 범위 |
|---|---|
| P0 | 회원 검색·필터, 대시보드 전체 통계, 계좌 상태 변경, 전체 거래 목록·상세 |
| P1 | 문의 관리, 충전 승인, 충전·잔액 원장, 사용자 알림 |
| P2 | 관리자 계정 관리, 감사 로그, 비밀번호 변경, 탈퇴 회원 정책 |
| P3 | 기간별 통계, CSV 내보내기 |

---

## 3. P0 API

### 3.1 관리자 대시보드

현재 프론트는 회원 목록 첫 페이지를 이용해 통계를 계산하므로 활성·정지 회원 수가 전체 기준이 아니다. 최근 가입 회원도 정렬이 보장되지 않는다.

#### `GET /api/admin/dashboard`

권장 응답:

```json
{
  "success": true,
  "message": "성공",
  "data": {
    "totalUsers": 100,
    "activeUsers": 93,
    "suspendedUsers": 7,
    "totalAccounts": 150,
    "totalOrders": 1200,
    "pendingOrders": 20,
    "executedOrders": 1100,
    "cancelledOrders": 80,
    "totalExecutedAmount": 540000000,
    "pendingInquiries": 4,
    "pendingChargeRequests": 2,
    "recentUsers": [],
    "recentOrders": []
  }
}
```

요구사항:

- 회원 수는 전체 활성 레코드 기준으로 계산한다.
- 최근 회원은 `createdAt DESC`, 기본 8건으로 반환한다.
- 최근 거래는 `executedAt DESC`, 기본 20건으로 반환한다.
- 아직 문의·충전 요청 도메인이 구현되지 않았다면 해당 집계는 1차 구현에서 제외할 수 있다.
- 기존 `UserRepository.countByIsActiveTrue()`, `OrderRepository.countByStatus()`, `sumExecutedAmount()`, `findTop20ByStatusOrderByExecutedAtDesc()`를 활용할 수 있다.

### 3.2 회원 서버 검색·필터·정렬

기존 API를 확장한다.

#### `GET /api/admin/users`

Query parameters:

| 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `query` | string | 아니오 | 회원번호, 아이디, 이름, 이메일 통합 검색 |
| `status` | enum | 아니오 | `ACTIVE`, `SUSPENDED` |
| `role` | enum | 아니오 | `USER`, `ADMIN` |
| `page` | number | 아니오 | 기본값 `0` |
| `size` | number | 아니오 | 기본값 `20`, 최대값 제한 필요 |
| `sort` | string | 아니오 | 기본값 `createdAt,desc` |

예시:

```http
GET /api/admin/users?query=fnel&status=ACTIVE&role=USER&page=0&size=20&sort=createdAt,desc
```

요구사항:

- 검색과 필터는 DB 쿼리에 반영한 뒤 페이징한다.
- 현재처럼 조회된 한 페이지를 애플리케이션에서 필터링하면 안 된다.
- 빈 문자열은 조건 없음으로 처리한다.
- 허용되지 않은 정렬 필드는 거부하거나 서버 기본 정렬로 대체한다.

### 3.3 전체 거래 관리

백엔드 `OrderRepository`에는 전체 주문 페이징, 상세, 집계 메서드가 있으나 관리자 Service·Controller·DTO가 없다.

#### `GET /api/admin/orders`

Query parameters:

| 이름 | 설명 |
|---|---|
| `query` | 주문번호, 회원 아이디, 계좌번호, 종목코드, 종목명 검색 |
| `status` | `PENDING`, `EXECUTED`, `CANCELLED` |
| `orderType` | `BUY`, `SELL` |
| `priceType` | `MARKET`, `LIMIT` |
| `stockCode` | 종목코드 |
| `from` | 조회 시작 일시 |
| `to` | 조회 종료 일시 |
| `page`, `size`, `sort` | 페이징 및 정렬 |

목록 항목:

```json
{
  "orderId": 1,
  "userId": 19,
  "loginId": "fnel2003",
  "accountId": 10,
  "accountNumber": "1234567890",
  "stockCode": "005930",
  "stockName": "삼성전자",
  "orderType": "BUY",
  "priceType": "LIMIT",
  "orderPrice": 70000,
  "execPrice": 69500,
  "quantity": 10,
  "status": "EXECUTED",
  "orderedAt": "2026-08-29T10:00:00",
  "executedAt": "2026-08-29T10:00:03"
}
```

#### `GET /api/admin/orders/{orderId}`

- 목록 항목 전체와 회원·계좌 정보를 반환한다.
- 존재하지 않는 주문은 `ORDER_NOT_FOUND`로 처리한다.

#### `PATCH /api/admin/orders/{orderId}/cancel`

관리자 강제 취소 정책을 사용하는 경우 구현한다.

```json
{
  "reason": "비정상 주문으로 관리자 취소"
}
```

요구사항:

- `PENDING` 주문만 취소할 수 있다.
- 지정가 매수 주문은 동결 금액을 반환한다.
- 이미 체결·취소된 주문은 충돌 응답을 반환한다.
- 주문 잠금, 잔액 반환, 상태 변경, 감사 로그를 하나의 트랜잭션에서 처리한다.

### 3.4 계좌별 정지·해제

`Account.suspend()`/`activate()`와 주문 차단 로직은 구현되어 있으나 관리자 API가 없다.

#### `PATCH /api/admin/accounts/{accountId}/status`

```json
{
  "status": "SUSPENDED",
  "reason": "이상 거래 확인"
}
```

응답은 기존 `AccountInfoResponse`에 회원 식별 정보를 추가한 관리자 DTO를 사용한다.

요구사항:

- 상태는 `ACTIVE`, `SUSPENDED`만 허용한다.
- 정지된 계좌는 로그인과 조회가 가능하고 매수·매도·주문 취소는 차단한다.
- 동일 상태 변경은 멱등 처리한다.
- 처리 관리자와 사유를 감사 로그에 남긴다.

별도 계좌 관리 화면을 지원하려면 다음도 구현한다.

```http
GET /api/admin/accounts?query=&status=&page=0&size=20&sort=createdAt,desc
GET /api/admin/accounts/{accountId}
```

---

## 4. P1 API

### 4.1 사용자 문의 및 관리자 답변

현재 `Inquiry` Entity와 Repository는 존재하지만 Controller, Service, 요청·응답 DTO는 비어 있다.

#### 사용자 API

```http
POST /api/inquiries
GET /api/inquiries?page=0&size=20
GET /api/inquiries/{inquiryId}
```

문의 생성 요청:

```json
{
  "title": "추가 충전 문의",
  "content": "충전 한도 초과로 추가 충전을 요청합니다."
}
```

#### 관리자 API

```http
GET /api/admin/inquiries?query=&status=PENDING&page=0&size=20&sort=createdAt,desc
GET /api/admin/inquiries/{inquiryId}
PATCH /api/admin/inquiries/{inquiryId}/answer
```

답변 요청:

```json
{
  "answer": "요청을 확인했습니다."
}
```

요구사항:

- 사용자는 자신의 문의만 조회할 수 있다.
- 관리자는 전체 문의를 조회한다.
- 미답변 문의를 먼저 표시하고 같은 상태에서는 최신순으로 정렬한다.
- 답변 시 `answeredBy`, `answeredAt`, `status=ANSWERED`를 함께 저장한다.
- 답변 완료 후 사용자 알림을 생성한다.
- 이미 답변한 문의를 수정할 수 있게 할지 정책 결정이 필요하다.

### 4.2 추가 충전 요청·승인

현재 자동 충전은 계좌별 3회로 제한되어 있으며, 한도 초과 후 요청을 저장하는 도메인은 없다. `charge_requests` 같은 별도 테이블이 필요하다.

권장 상태:

```text
PENDING / APPROVED / REJECTED
```

#### 사용자 API

```http
POST /api/accounts/{accountId}/charge-requests
GET /api/accounts/{accountId}/charge-requests?page=0&size=20
GET /api/accounts/{accountId}/charge-requests/{requestId}
```

요청 예시:

```json
{
  "amount": 10000000,
  "reason": "투자 학습을 위한 추가 가상캐시 요청"
}
```

#### 관리자 API

```http
GET /api/admin/charge-requests?query=&status=PENDING&page=0&size=20&sort=requestedAt,desc
GET /api/admin/charge-requests/{requestId}
PATCH /api/admin/charge-requests/{requestId}/decision
```

처리 요청:

```json
{
  "decision": "APPROVED",
  "reason": "추가 충전을 승인합니다."
}
```

요구사항:

- 본인 소유 계좌만 요청할 수 있다.
- 계좌별 미처리 요청 중복 생성 방지 정책이 필요하다.
- 승인 시 `balance`와 `baseBalance`를 동일 금액 증가시킨다.
- 승인·거절은 1회만 처리할 수 있어야 한다.
- 요청 행 잠금, 계좌 잔액 변경, 원장 생성, 알림 생성, 감사 로그를 하나의 트랜잭션에서 처리한다.
- 승인 금액 허용 범위와 일·월 누적 한도 정책을 서버에서 검증한다.

### 4.3 충전 및 잔액 변동 원장

현재 계좌에는 최종 잔액과 충전 횟수만 있고 변경 이력이 없다. `account_transactions` 또는 동등한 원장 테이블이 필요하다.

권장 거래 유형:

```text
INITIAL_GRANT / AUTO_CHARGE / ADMIN_CHARGE / ADMIN_DEDUCTION / ORDER_BUY / ORDER_SELL / ORDER_REFUND
```

#### 사용자 API

```http
GET /api/accounts/{accountId}/transactions?page=0&size=20&sort=createdAt,desc
```

#### 관리자 API

```http
GET /api/admin/accounts/{accountId}/transactions?page=0&size=20&sort=createdAt,desc
POST /api/admin/accounts/{accountId}/adjustments
```

관리자 조정 요청:

```json
{
  "type": "ADMIN_CHARGE",
  "amount": 10000000,
  "reason": "이벤트 보상 지급"
}
```

원장 필수 필드:

- 거래 ID, 계좌 ID
- 거래 유형
- 증감 금액
- 변경 전·후 잔액
- 연결된 주문·충전 요청 ID
- 처리 관리자 ID
- 처리 사유
- 생성 시각

원장은 수정·삭제하지 않는 append-only 방식을 권장한다.

### 4.4 관리자 알림 발송

알림 조회와 내부 `NotificationService.notify()`는 구현되어 있지만 관리자 발송 API는 없다.

```http
POST /api/admin/notifications/users/{userId}
POST /api/admin/notifications/broadcast
```

```json
{
  "title": "계좌 상태 변경 안내",
  "content": "계좌 거래 정지가 해제되었습니다.",
  "type": "SYSTEM"
}
```

요구사항:

- 문의 답변, 충전 승인·거절, 회원·계좌 상태 변경은 도메인 서비스에서 자동 알림을 생성한다.
- 전체 발송은 대량 insert·비동기 처리 여부를 검토한다.
- 기존 DB 저장 후 STOMP 전송 방식을 유지한다.

---

## 5. P2 API

### 5.1 관리자 계정 관리

현재 일반 회원가입 API에 `role=ADMIN`과 `adminCode`를 전달하면 관리자 생성이 가능하지만 관리자 전용 관리 API는 없다.

```http
GET /api/admin/admins?page=0&size=20&status=ACTIVE
GET /api/admin/admins/{adminId}
POST /api/admin/admins
PATCH /api/admin/admins/{adminId}/status
```

관리자 생성 요청 예시:

```json
{
  "loginId": "admin02",
  "password": "temporary-password",
  "name": "운영 관리자",
  "email": "admin02@example.com"
}
```

요구사항:

- 공개 관리자 가입 코드를 프론트에 노출하지 않는다.
- 자기 계정 정지를 막는다.
- 마지막 활성 관리자 정지를 막는다.
- 임시 비밀번호와 최초 로그인 변경 정책을 결정한다.
- 관리자 생성·정지 이력을 감사 로그에 남긴다.

### 5.2 관리자 작업 감사 로그

회원·계좌 상태 변경, 충전 승인, 주문 강제 취소 등 관리자 작업을 기록할 테이블과 API가 없다.

```http
GET /api/admin/audit-logs?action=&adminId=&targetType=&targetId=&from=&to=&page=0&size=20
GET /api/admin/audit-logs/{auditLogId}
```

필수 필드:

- 작업 관리자 ID·아이디
- action
- target type·ID
- 변경 전·후 값
- 사유
- 요청 IP
- 생성 시각

감사 로그는 관리자도 수정·삭제할 수 없게 한다.

### 5.3 비밀번호 확인·변경

일반 사용자와 관리자 모두 사용할 비밀번호 확인·변경 API가 없다.

```http
POST /api/users/me/password/verify
PATCH /api/users/me/password
```

확인 요청:

```json
{
  "password": "current-password"
}
```

변경 요청:

```json
{
  "currentPassword": "current-password",
  "newPassword": "new-password"
}
```

요구사항:

- 새 비밀번호는 기존 회원가입과 같은 정책을 적용한다.
- 변경 성공 시 기존 Refresh Token을 폐기한다.
- 관리자 생성·상태 변경 같은 민감 작업에 재인증을 요구할지는 별도 결정한다.

### 5.4 탈퇴 회원 조회 정책

현재 프론트 필터에는 `WITHDRAWN`이 있지만 백엔드 `UserStatus`는 `ACTIVE`, `SUSPENDED`만 존재한다. 탈퇴 회원은 `isActive=false`로 처리되고 개인정보가 익명화되며 관리자 조회에서 제외된다.

아래 중 하나를 확정해야 한다.

1. 탈퇴 회원을 관리 대상에서 제외하고 프론트의 `WITHDRAWN` 필터를 제거한다.
2. 익명화된 탈퇴 이력만 별도 조회한다.
3. `WITHDRAWN`을 정식 상태로 도입하고 보존 정책을 다시 정의한다.

2번을 선택하는 경우:

```http
GET /api/admin/users/withdrawn?page=0&size=20&sort=deletedAt,desc
```

개인정보 보존 범위는 현재 익명화 정책과 충돌하지 않게 결정해야 한다.

---

## 6. P3 API

### 6.1 기간별 통계

대시보드 차트가 필요할 때 구현한다.

```http
GET /api/admin/statistics/users?from=2026-08-01&to=2026-08-31&interval=DAY
GET /api/admin/statistics/orders?from=2026-08-01&to=2026-08-31&interval=DAY
GET /api/admin/statistics/amounts?from=2026-08-01&to=2026-08-31&interval=DAY
```

`interval`은 `DAY`, `WEEK`, `MONTH`를 지원한다.

### 6.2 CSV 내보내기

```http
GET /api/admin/users/export?format=csv
GET /api/admin/orders/export?format=csv
GET /api/admin/charge-requests/export?format=csv
GET /api/admin/audit-logs/export?format=csv
```

요구사항:

- 화면과 동일한 검색·필터 조건을 지원한다.
- 개인정보가 포함되므로 `ROLE_ADMIN` 검증과 감사 로그를 적용한다.
- 데이터 양이 크면 비동기 생성과 만료 다운로드 URL 방식을 사용한다.

---

## 7. 공통 백엔드 요구사항

### 보안

- `/api/admin/**`는 `ROLE_ADMIN`만 허용한다.
- 경로의 사용자·계좌·주문 ID를 신뢰하지 말고 서버에서 존재 여부와 상태를 검증한다.
- 관리자 변경 작업에는 처리 관리자 ID와 사유를 기록한다.
- 관리자 가입 코드, JWT secret 등 환경변수는 응답·로그에 노출하지 않는다.

### 페이징·정렬

- 목록 API는 `Page<T>` 기반으로 통일한다.
- 기본 `page=0`, `size=20`을 사용하고 최대 `size`를 제한한다.
- 최신순이 필요한 API는 서버에서 기본 정렬을 명시한다.
- 검색 후 페이징해야 하며, 페이지 조회 후 메모리 필터링하지 않는다.

### 동시성·트랜잭션

- 충전 승인, 잔액 조정, 주문 강제 취소는 대상 행 잠금이 필요하다.
- 승인·취소 요청은 중복 실행되어도 금액이 두 번 반영되지 않게 한다.
- 상태 변경, 원장, 알림, 감사 로그는 가능한 한 동일 트랜잭션에서 처리한다.
- STOMP 알림은 기존 방식대로 DB 커밋 후 전송한다.

### 오류 응답

기존 `CustomException`/`ErrorCode` 규칙을 유지하고 최소한 다음 오류를 구분한다.

```text
ADMIN_FORBIDDEN
USER_NOT_FOUND
ACCOUNT_NOT_FOUND
ORDER_NOT_FOUND
INQUIRY_NOT_FOUND
CHARGE_REQUEST_NOT_FOUND
INVALID_STATUS_TRANSITION
ALREADY_PROCESSED
SELF_ADMIN_SUSPEND_NOT_ALLOWED
LAST_ADMIN_SUSPEND_NOT_ALLOWED
```

### 테스트

- Controller 권한 테스트: USER 403, ADMIN 성공
- Service 상태 전이 테스트
- 검색·필터·페이징 통합 테스트
- 충전 중복 승인 동시성 테스트
- 주문 강제 취소와 동결 금액 반환 테스트
- 계좌·회원 정지 상태에서 주문 차단 테스트
- 감사 로그 및 알림 생성 테스트

---

## 8. 프론트 연동 완료 기준

- 관리자 대시보드 수치가 전체 DB 기준으로 표시된다.
- 회원 검색·상태 필터·정렬이 페이지 전체 데이터에 적용된다.
- 회원과 계좌의 정지·해제가 즉시 화면에 반영된다.
- 전체 주문을 검색·필터·상세 조회할 수 있다.
- 문의 답변과 충전 승인 결과가 사용자 알림에 표시된다.
- 모든 금액 변경을 원장에서 추적할 수 있다.
- 관리자 변경 작업을 감사 로그에서 조회할 수 있다.
- API 명세의 enum과 프론트 TypeScript 타입이 일치한다.

## 9. 구현 전 결정이 필요한 정책

1. 탈퇴 회원을 관리자 화면에서 조회할지 여부
2. 관리자가 미체결 주문을 강제 취소할 수 있는지 여부
3. 추가 충전 요청 가능 금액과 누적 한도
4. 동일 계좌의 중복 충전 요청 허용 여부
5. 답변 완료된 문의 수정 가능 여부
6. 관리자를 기존 회원 승격 방식으로 만들지, 별도 생성 방식으로 만들지
7. 기간별 통계와 CSV 기능을 이번 개발 범위에 포함할지 여부

