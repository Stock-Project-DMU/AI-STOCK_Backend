-- =====================================================
-- AI STOCK MySQL Schema (최종본 v12)
-- 변경사항 v11 → v12:
--   1. news_briefing_settings, news_briefings 테이블 신규 추가 (14, 15번째 테이블)
--      (feature/ai-news — 맞춤형 뉴스 브리핑. AI 재무설계사와 달리 대화형이 아니라,
--       사용자가 미리 골라둔 언론사 하나를 기준으로 스케줄러가 매일 오늘의 시황을
--       요약해두는 단방향 비서. v5→v6에서 "Tavily 즉석 검색으로 전환"하며 제거했던
--       market_briefings의 개념적 후속이지만, 이번엔 즉석 검색이 아니라 "언론사 선택
--       기반 자동 생성" 방식이라 완전히 새로 설계했다. 상세 설계 배경은 각 테이블
--       주석 참고.)
--   2. notifications.type ENUM에 'NEWS' 값 추가
--      (feature/ai-news — 브리핑이 생성되면 알림도 함께 발송하기로 확정, 2026-08-24)
-- =====================================================
-- 변경사항 v10 → v11:
--   1. simulations 테이블에 investment_amount 컬럼 추가
--      (feature/simulation 1차 PR — 목표 도달 시뮬레이션은 사용자의 실제 보유
--       종목/수량과 무관하게 "이 금액을 투자한다면"이라는 가정으로 계산한다.
--       기존 target_amount(목표 금액)만으로는 시작 원금을 알 수 없어 도달 시점
--       계산이 성립하지 않아서 추가했다. scenario_data의 각 포인트도 이 값의
--       복리 계산 결과(포트폴리오 평가금액)이며, 필드명은 종목 주당가(price)와
--       혼동을 피하기 위해 value로 통일한다(아래 10번 섹션 주석 참고).)
-- =====================================================
-- 변경사항 v9 → v10:
--   1. accounts 테이블에 account_name, charge_count 컬럼 추가
--      (feature/mypage-account — 유저 1명이 최대 3개까지 가상계좌를 만들 수 있도록
--       변경. account_name은 계좌 구분용 이름("계좌 A" 등)이며 CreateAccountRequest에서
--       필수값으로 받는다(자동 기본값 없음). charge_count는 계좌별 가상캐시
--       충전 횟수 — 3회까지는 자동 충전, 그 이상은 관리자 승인 필요(서비스
--       계층에서 CHARGE_LIMIT_EXCEEDED로 차단, 실제 승인 워크플로우는 이번
--       범위 밖). 계좌 개수 제한(최대 3개)은 DB 제약이 아니라 AccountService에서
--       INSERT 전에 COUNT로 검증한다 — accounts.user_id는 이미 v1부터 FK만 있고
--       UNIQUE가 아니라서(1:N 관계) 스키마 자체는 원래도 여러 계좌를 막지 않았다.
-- =====================================================
-- 변경사항 v8 → v9:
--   1. orders 테이블에 version 컬럼 추가
--      (낙관적 락 — accounts.version과 동일한 목적. feature/order-limit에서
--       Order 행 자체를 비관적 락(findByIdForUpdate 등)으로만 보호하고 있었는데,
--       OrderRepository의 다른 잠금 없는 조회 메서드로 주문을 가져와 수정하는
--       경로가 생기면 동시성 보호를 못 받는 구조적 위험이 있어 최후의 안전망으로 추가)
-- =====================================================
-- 변경사항 v7 → v8:
--   1. inquiries 테이블 신규 추가 (13번째 테이블)
--      사용자 문의 작성 + 관리자 확인/답변 기능
--      (관리자 페이지 "문의 관리" 기능에 대응)
-- =====================================================
-- 변경사항 v6 → v7:
--   1. users 테이블에 status 컬럼 추가
--      (관리자에 의한 로그인 차단 — 기존 is_active/deleted_at의
--       "본인 탈퇴"와는 별개 개념. 관리자 페이지 사용자 관리 기능)
--   2. accounts 테이블에 status 컬럼 추가
--      (관리자에 의한 계좌 기능 정지 — 거래만 차단, 로그인은 가능.
--       관리자 페이지 계좌 관리 기능)
--   3. users.role은 기존과 동일하게 유지 (ENUM('USER','ADMIN'))
--      회원가입 시 role=ADMIN 선택 + 관리자 코드 검증 로직은
--      서비스 계층(AuthService)에서 처리, 스키마 변경 없음
-- =====================================================
-- 변경사항 v5 → v6:
--   1. market_briefings 테이블 제거
--      (뉴스 서비스가 Tavily 즉석 검색으로 변경됨)
--   2. accounts.version 컬럼 추가
--      (낙관적 락 적용 - 지정가 동시성 처리)
-- =====================================================

CREATE DATABASE IF NOT EXISTS aistock
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE aistock;

CREATE TABLE IF NOT EXISTS planning_preferences (
    user_id BIGINT NOT NULL PRIMARY KEY,
    version BIGINT,
    selections TEXT NOT NULL
);

-- 장기 적립식 목표 UI용 저장소. 기존 종목별 simulations와 계산 입력이 다르다.
-- 실제 운영 DB에는 아래 CREATE TABLE을 별도 적용한다(전체 schema.sql 재실행 금지).
CREATE TABLE IF NOT EXISTS goal_plans (
    plan_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    goal VARCHAR(20) NOT NULL,
    monthly_payment BIGINT NOT NULL,
    years INT NOT NULL,
    annual_return DOUBLE NOT NULL,
    aggressive BOOLEAN NOT NULL,
    is_saved BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL,
    INDEX idx_goal_plan_user (user_id)
);

-- =====================================================
-- 1. 회원 (users)
-- =====================================================
/*
  [탈퇴 처리 전략 — 정정]
  주의: FK의 ON DELETE CASCADE는 부모 행(users)이 실제 DELETE될 때만 발동한다.
  탈퇴 처리는 UPDATE users SET deleted_at = NOW() ... 형태의 soft delete이므로,
  자식 테이블(social_accounts, investment_profile, accounts, watchlist,
  ai_planning_sessions, simulations, recent_viewed, notifications,
  news_briefing_settings, news_briefings)은 자동으로 삭제되지 않는다.
  따라서 탈퇴 서비스 로직에서 아래 순서로 명시적 삭제를 수행해야 한다.

  1) 자식 테이블 명시적 DELETE (서비스 코드에서 각 Repository의
     deleteByUserId(userId) 등을 통해 수행)
       DELETE FROM social_accounts        WHERE user_id = ?;
       DELETE FROM investment_profile     WHERE user_id = ?;
       DELETE FROM watchlist              WHERE user_id = ?;
       DELETE FROM ai_planning_sessions   WHERE user_id = ?;  -- messages는 FK CASCADE로 자동 삭제
       DELETE FROM simulations            WHERE user_id = ?;
       DELETE FROM recent_viewed          WHERE user_id = ?;
       DELETE FROM notifications          WHERE user_id = ?;
       DELETE FROM inquiries              WHERE user_id = ?;  -- v8 추가 (answered_by로 참조된 다른 문의는 영향 없음)
       DELETE FROM news_briefing_settings WHERE user_id = ?;  -- v12 추가
       DELETE FROM news_briefings         WHERE user_id = ?;  -- v12 추가
       DELETE FROM accounts               WHERE user_id = ?;  -- holdings/orders는 FK CASCADE로 자동 삭제

  2) Redis 정리 (RedisTokenService.deleteRefreshToken(userId) 등 호출)
       auth:refresh:{userId} 삭제

  3) users 테이블 PII 익명화 (마지막 단계)
     UPDATE users SET
       email      = NULL,
       login_id   = CONCAT('deleted_', user_id),
       name       = '탈퇴회원',
       birthdate  = NULL,
       password   = NULL,
       is_active  = 0,
       deleted_at = NOW()
     WHERE user_id = ?;

  ※ 실제 구현은 탈퇴 관련 기능 브랜치(예: feature/auth-logout 또는
     feature/user-withdrawal)에서 서비스 코드로 작성한다. 이 스키마
     파일은 삭제 순서와 CASCADE 범위에 대한 설계 지침만 명시한다.

  [이메일 NULL 허용 이유]
  카카오는 이메일 동의 거부 가능 → NULL 허용 필요
  UNIQUE 제약 유지 → NULL끼리는 중복 아님 (MySQL 기준)

  [status vs is_active/deleted_at — 개념 구분 (v7 추가)]
  is_active + deleted_at : 회원 "본인"이 탈퇴한 경우 (soft delete, PII 익명화 동반)
  status                 : "관리자"가 로그인을 차단한 경우 (정지, PII는 그대로 유지)
                           탈퇴와 달리 관리자가 다시 ACTIVE로 되돌릴 수 있다.
  로그인 시 검증 순서: is_active=0(탈퇴) 또는 status='SUSPENDED'(정지) 둘 중
  하나라도 해당하면 로그인 차단. 에러 메시지는 서비스 계층에서 구분해서 안내.
*/
CREATE TABLE users (
    user_id     BIGINT          NOT NULL AUTO_INCREMENT,
    login_id    VARCHAR(50)     UNIQUE,              -- 일반 로그인 아이디 (소셜·탈퇴=NULL)
    password    VARCHAR(255),                        -- BCrypt 암호화 (소셜·탈퇴=NULL)
    name        VARCHAR(50)     NOT NULL,            -- 탈퇴 후 '탈퇴회원' 으로 익명화
    birthdate   DATE,                                -- 소셜 미제공 시 NULL, 탈퇴 후 NULL
    email       VARCHAR(100)    UNIQUE,              -- NOT NULL 제거 (소셜 이메일 미제공 대응)
    role        ENUM('USER','ADMIN')
                                NOT NULL DEFAULT 'USER',
    status      ENUM('ACTIVE','SUSPENDED')
                                NOT NULL DEFAULT 'ACTIVE',  -- v7 추가: 관리자에 의한 로그인 차단
    is_active   TINYINT(1)      NOT NULL DEFAULT 1,  -- 1=활성, 0=탈퇴 (본인 탈퇴 여부)
    deleted_at  DATETIME        NULL,                -- 탈퇴 시각 (활성 회원은 NULL)
    created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP
                                         ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id),
    INDEX idx_email  (email),
    INDEX idx_active (is_active),
    INDEX idx_status (status)                        -- v7 추가: 관리자 페이지 정지 회원 필터링용
) ENGINE=InnoDB;

-- =====================================================
-- 2. 소셜 계정 (social_accounts)
-- =====================================================
/*
  [설계 이유]
  users 테이블에서 provider/provider_id를 분리
  → 다중 소셜 연동 지원 (카카오 + 구글 동시 가능)
  → 일반 로그인 유저에게 NULL 컬럼 없애기 위함
  → 탈퇴 시 CASCADE DELETE로 자동 삭제
  (단, users는 soft delete이므로 실제로는 탈퇴 서비스 로직에서
   social_accounts를 명시적으로 DELETE해야 한다. 위 1번 주석 참고.
   여기서의 CASCADE는 accounts 등 실제 DELETE가 발생하는 케이스에서
   함께 동작하는 하위 관계에 한해 유효하다.)

  [소셜 로그인 조회]
  SELECT u.* FROM social_accounts sa
  JOIN users u ON u.user_id = sa.user_id
  WHERE sa.provider = 'KAKAO' AND sa.provider_id = '12345678';

  [provider별 제공 데이터]
  Kakao : id(필수), email(동의), 닉네임, birthday+birthyear 분리
  Naver : id(필수), email(동의), 실명,   birthday+birthyear 분리
  Google: sub(필수), email(기본), 실명,  생년월일 미제공→NULL
*/
CREATE TABLE social_accounts (
    social_id   BIGINT          NOT NULL AUTO_INCREMENT,
    user_id     BIGINT          NOT NULL,
    provider    ENUM('KAKAO','NAVER','GOOGLE') NOT NULL,
    provider_id VARCHAR(255)    NOT NULL,            -- 소셜 서비스 발급 고유 ID
    created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (social_id),
    UNIQUE KEY uq_provider (provider, provider_id),  -- 동일 소셜 계정 중복 방지
    INDEX idx_user_social (user_id),
    FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- =====================================================
-- 3. 투자성향 설문 결과 (investment_profile)
-- =====================================================
/*
  투자성향 (5단계)
    1: 안정형       - 원금 보존 최우선, 예금/채권 위주
    2: 안정추구형   - 낮은 수익이라도 손실 최소화, 채권/배당주 중심
    3: 위험중립형   - 적당한 수익과 적당한 위험 균형
    4: 적극투자형   - 높은 수익 위해 일정 손실 감수, 성장주/ETF
    5: 공격투자형   - 최대 수익, 고위험 자산(레버리지/테마주) OK

  자금성향 (4단계)
    1: 안정저축형   - 목돈 모으기, 적금/CMA 위주
    2: 수익추구형   - 투자 수익 목적, 주식/펀드 중심
    3: 목표달성형   - 내 집 마련, 은퇴 등 구체적 목표
    4: 자유소비형   - 여유 자금 운용, 유동성 중시

  investment_level (영문 통일 — 다른 ENUM들과 네이밍 일관성 유지)
    BEGINNER     : 초보자
    INTERMEDIATE : 중급자
    EXPERT       : 전문가
*/
CREATE TABLE investment_profile (
    profile_id            BIGINT      NOT NULL AUTO_INCREMENT,
    user_id               BIGINT      NOT NULL,
    investment_tendency   TINYINT     NOT NULL
                          COMMENT '1:안정형 2:안정추구형 3:위험중립형 4:적극투자형 5:공격투자형',
    fund_tendency         TINYINT     NOT NULL
                          COMMENT '1:안정저축형 2:수익추구형 3:목표달성형 4:자유소비형',
    investment_level      ENUM('BEGINNER','INTERMEDIATE','EXPERT')
                                      NOT NULL DEFAULT 'BEGINNER',
    survey_answers        JSON,                      -- 설문 문항별 원본 응답
    created_at            DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP
                                               ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (profile_id),
    UNIQUE KEY uq_user_profile (user_id),            -- 1인 1프로필
    FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- =====================================================
-- 4. 모의투자 계좌 (accounts)
-- =====================================================
/*
  [계좌 개수 — v10]
  유저 1명이 최대 3개까지 가상계좌를 만들 수 있다(예: "계좌 A"는 안정적으로,
  "계좌 B"는 공격적으로 운용해보는 식). accounts.user_id는 처음부터 FK만 있고
  UNIQUE가 아니었으므로(1:N) 스키마 변경은 필요 없고, 개수 제한(3개)은
  AccountService.createAccount()에서 COUNT 후 검증한다.

  [총 자산 계산 (DB 컬럼 없음 — 실시간 계산, 계좌 단위)]
  총 자산 = (balance + frozen_balance)
            + Σ(holdings.quantity × Redis stock:price:{stockCode})
  수익률 = (총 자산 - base_balance) / base_balance × 100

  [잔고 구조]
  base_balance    : 최초 지급 + 누적 충전 금액 (수익률 계산 기준 — 충전 시 함께 올라감)
  balance         : 즉시 사용 가능한 현금 잔고
  frozen_balance  : 지정가 주문 예약 잠금 금액 (balance에서 이미 차감)

  frozen_balance를 accounts에 두는 이유:
  → 잔고 상태를 한 행에서 즉시 파악 가능
  → orders 테이블에 분산하면 매번 SUM 쿼리 필요

  [frozen_balance 처리 흐름]
  지정가 매수 등록: balance -= 주문금액, frozen_balance += 주문금액
  지정가 체결:     frozen_balance -= 주문금액 (balance는 이미 차감 완료)
  지정가 취소:     balance += 주문금액,       frozen_balance -= 주문금액

  [가상캐시 충전 — v10]
  POST /api/accounts/{accountId}/charge 호출 시 balance/base_balance를 charge_amount만큼
  같이 올리고(수익률에 공짜 충전분이 반영되지 않도록) charge_count를 1 증가시킨다.
  charge_count가 3 이상이면 CustomException(ErrorCode.CHARGE_LIMIT_EXCEEDED)로 막는다.

  [한도 초과 시 관리자 승인 절차 — v13]
  charge_count 3회를 넘긴 사용자는 charge_requests 테이블(18번 섹션)에 승인 요청을
  남기고 관리자가 승인/거절한다. 승인 시 accounts.applyAdminCharge()로 balance/
  base_balance만 올라가고 charge_count는 그대로 둔다(자동 충전 3회 한도와는 별개
  트랙이라 재사용하지 않는다) — v10 시점엔 이 절차가 "관리자 문의로 별도 요청"
  수준의 막연한 안내였으나, v13에서 전용 테이블·API로 정식 구현됐다.

  [version 컬럼 - 낙관적 락]
  동시성 문제 해결: 지정가 주문 동시 체결 시 잔고 오류 방지
  UPDATE accounts
  SET balance = ?, frozen_balance = ?, version = version + 1
  WHERE account_id = ? AND version = ?
  → 충돌 감지 시 재시도 (모의투자 특성상 충돌 빈도 낮음)

  [status 컬럼 — 관리자 계좌 정지 (v7 추가)]
  users.status(로그인 차단)와는 별개 개념.
  accounts.status = 'SUSPENDED' 인 경우:
    - 로그인은 가능 (users.status가 ACTIVE라면)
    - 매수/매도 주문만 차단 (order-market, order-limit 서비스 계층에서
      OrderService.createOrder() 진입 시 accounts.status 확인 후
      SUSPENDED면 CustomException(ErrorCode.ACCOUNT_SUSPENDED) throw)
    - 조회(마이페이지, 보유종목 등)는 계속 가능
*/
CREATE TABLE accounts (
    account_id      BIGINT          NOT NULL AUTO_INCREMENT,
    user_id         BIGINT          NOT NULL,
    account_name    VARCHAR(50)     NOT NULL,                 -- v10 추가: 계좌 구분용 이름 (예: "계좌 A")
    account_number  VARCHAR(20)     NOT NULL UNIQUE,
    opened_at       DATE            NOT NULL,
    base_balance BIGINT          NOT NULL DEFAULT 10000000, -- 지급/충전 누계 기준 (수익률 기준)
    balance         BIGINT          NOT NULL DEFAULT 10000000, -- 즉시 사용 가능한 현금 잔고
    frozen_balance  BIGINT          NOT NULL DEFAULT 0,        -- 지정가 주문 예약 잠금 금액
    charge_count    TINYINT         NOT NULL DEFAULT 0,        -- v10 추가: 가상캐시 충전 횟수 (3회까지 자동, 이후 관리자 승인)
    version         BIGINT          NOT NULL DEFAULT 0,        -- 낙관적 락용 버전 번호
    status          ENUM('ACTIVE','SUSPENDED')
                                    NOT NULL DEFAULT 'ACTIVE', -- v7 추가: 관리자에 의한 거래 정지
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (account_id),
    INDEX idx_account_status (status),               -- v7 추가: 관리자 페이지 정지 계좌 필터링용
    INDEX idx_account_user (user_id),                -- v10 추가: "내 계좌 목록" 조회(user_id 기준 최대 3건)용
    FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- =====================================================
-- 5. 보유 종목 (holdings)
-- =====================================================
/*
  [데이터 출처]
  stock_code, stock_name : LS증권 WebSocket 수신 데이터
  quantity, avg_price    : 주문 체결 시 서버 내부 계산

  [평가손익 계산]
  평가손익 = (Redis stock:price - avg_price) × quantity
*/
CREATE TABLE holdings (
    holding_id  BIGINT          NOT NULL AUTO_INCREMENT,
    account_id  BIGINT          NOT NULL,
    stock_code  VARCHAR(10)     NOT NULL,            -- LS증권 종목코드 (예: 005930)
    stock_name  VARCHAR(50)     NOT NULL,            -- LS증권 종목명
    quantity    INT             NOT NULL DEFAULT 0,
    avg_price   BIGINT          NOT NULL DEFAULT 0,  -- 평균 매입가 (원)
    updated_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP
                                         ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (holding_id),
    UNIQUE KEY uq_account_stock (account_id, stock_code),
    FOREIGN KEY (account_id) REFERENCES accounts(account_id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- =====================================================
-- 6. 주문 내역 (orders)
-- =====================================================
/*
  [체결가 확정 방식]
  order_price : 주문 요청 시점 가격
  exec_price  : 서버가 Redis stock:price에서 직접 확정
                → 프론트 전송값 그대로 사용 안 함 (보안)

  [현재가 vs 지정가 체결 로직]
  현재가: Redis 현재가 조회 → 즉시 체결
  지정가: Redis pending:orders 등록 → tick 수신 시 조건 체크
          매수: 현재가 <= 지정가 → 체결
          매도: 현재가 >= 지정가 → 체결

  [idx_stock_pending 인덱스]
  tick 수신마다 WHERE stock_code = ? AND status = 'PENDING' 쿼리 실행
  → 빈번한 조회이므로 복합 인덱스 필수

  [관리자 페이지 활용 (v7)]
  GET /api/admin/trades       : 전체 사용자 주문 목록 (ordered_at DESC)
  GET /api/admin/trades/{id}  : 주문 상세 (account → user JOIN)
  GET /api/admin/dashboard    : 최근거래 N건, 총 거래량(SUM/COUNT, status='EXECUTED' 기준)

  [version 컬럼 - 낙관적 락 (v9 추가)]
  feature/order-limit에서 같은 주문을 동시에 체결(OrderExecutionService.execute)/
  취소(OrderService.cancelOrder)하려는 경합을 findByIdForUpdate/
  findByOrderIdAndAccountIdForUpdate의 비관적 락(SELECT ... FOR UPDATE)으로만 막고
  있었는데, OrderRepository에는 그 외에도 잠금 없는 조회 메서드(findByOrderIdAndAccountId,
  findAllOrdersWithUser, findOrderWithUserById, 상속받은 findById 등 관리자 기능용)가
  함께 존재해 향후 그 경로로 order.execute()/order.cancel()을 호출하면 동시성 보호를
  못 받는 구조적 위험이 있었다. accounts.version과 동일한 패턴으로 낙관적 락을 추가해,
  "어떤 조회 경로로 가져왔든" JPA가 자동으로 동시 수정 충돌을 막도록 한다. 비관적 락은
  체결 파이프라인의 주된 경로에서 그대로 유지한다(두 트랜잭션이 겹치지 않고 순서대로
  처리되게 하는 목적은 비관적 락이 담당하고, version은 최후의 안전망 역할).
*/
CREATE TABLE orders (
    order_id    BIGINT          NOT NULL AUTO_INCREMENT,
    account_id  BIGINT          NOT NULL,
    stock_code  VARCHAR(10)     NOT NULL,
    stock_name  VARCHAR(50)     NOT NULL,
    order_type  ENUM('BUY','SELL')      NOT NULL,
    price_type  ENUM('LIMIT','MARKET')  NOT NULL DEFAULT 'LIMIT',
    order_price BIGINT          NOT NULL,            -- 주문 요청 가격
    exec_price  BIGINT,                              -- 실제 체결가 (미체결=NULL)
    quantity    INT             NOT NULL,            -- 주문 수량 (전량 체결 방식)
    status      ENUM('PENDING','EXECUTED','CANCELLED') NOT NULL DEFAULT 'PENDING',
    version     BIGINT          NOT NULL DEFAULT 0,  -- v9 추가: 낙관적 락용 버전 번호
    ordered_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    executed_at DATETIME,                            -- 체결 시각 (미체결=NULL)
    PRIMARY KEY (order_id),
    INDEX idx_account_order (account_id, ordered_at),
    INDEX idx_stock_pending (stock_code, status),   -- 지정가 체결 조건 체크용
    FOREIGN KEY (account_id) REFERENCES accounts(account_id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- =====================================================
-- 7. 관심 종목 (watchlist)
-- =====================================================
CREATE TABLE watchlist (
    watchlist_id BIGINT         NOT NULL AUTO_INCREMENT,
    user_id      BIGINT         NOT NULL,
    stock_code   VARCHAR(10)    NOT NULL,
    stock_name   VARCHAR(50)    NOT NULL,
    added_at     DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (watchlist_id),
    UNIQUE KEY uq_user_stock (user_id, stock_code),
    FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- =====================================================
-- 8. AI 재무설계 대화 세션 (ai_planning_sessions)
-- =====================================================
CREATE TABLE ai_planning_sessions (
    session_id  BIGINT          NOT NULL AUTO_INCREMENT,
    user_id     BIGINT          NOT NULL,
    title       VARCHAR(100),                        -- 상담 제목 (첫 메시지 기반 자동 생성)
    status      ENUM('ACTIVE','CLOSED') NOT NULL DEFAULT 'ACTIVE',
    created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP
                                         ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (session_id),
    INDEX idx_user_session (user_id),
    FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- =====================================================
-- 9. AI 재무설계 대화 메시지 (ai_planning_messages)
-- =====================================================
/*
  [Gemini 대화 이력 관리]
  Gemini API는 stateless → 대화 이력 직접 저장 필요
  재접속 시 이전 대화 복원 + Gemini 프롬프트 구성에 사용

  [토큰 관리]
  prompt_tokens 누적으로 최근 N개 메시지만 전송 결정
  → 컨텍스트 윈도우 초과 방지

  [세션 = 하나의 화제 단위] (2026-08-06 논의 후 확정)
  한 세션(채팅방) 안에서 화제가 바뀌는 걸 자동으로 감지해 나누는 기능(topic_seq 등)은
  검토했다가 제거했다 — 사용자는 세션 하나를 "하나의 큰 주제"(예: 삼성그룹 전체) 단위로
  쓰고, 완전히 다른 주제(SK그룹 등)는 새 세션을 만들어 시작하는 방식이 실제 사용 패턴이라,
  세션 내 화제 자동 분기 자체가 불필요했다. 화면은 세션 안 메시지를 그냥 시간순으로 보여주면
  되고(카카오톡 채팅방과 동일한 스크롤 방식), AI가 세션 안 대화를 잊지 않게 하는 것은
  AiPlanningService.buildHistory()의 히스토리 유지 정책으로 별도 해결한다.
*/
CREATE TABLE ai_planning_messages (
    message_id    BIGINT        NOT NULL AUTO_INCREMENT,
    session_id    BIGINT        NOT NULL,
    role          ENUM('USER','AI') NOT NULL,
    content       TEXT          NOT NULL,
    prompt_tokens INT,                               -- Gemini 토큰 사용량 (AI 응답만)
    created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (message_id),
    INDEX idx_session_msg (session_id, created_at),
    FOREIGN KEY (session_id) REFERENCES ai_planning_sessions(session_id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- =====================================================
-- 10. 목표 도달 시뮬레이션 (simulations)
-- =====================================================
/*
  [데이터 출처]
  dart_data     : Open DART API (재무제표, 공시)
  news_data     : 네이버 뉴스 검색 API (NCP API Hub) — 원래 Tavily였으나 feature/ai-planning에서
                  2026-08-05 네이버로 교체(관련 코드는 2026-08-06 완전 삭제), 이 기능도 동일하게 맞춘다
  scenario_data : Gemini는 시나리오별 월 복리 성장률(스칼라) + 근거만 반환하고,
                  실제 date+value 곡선은 서버가 investment_amount를 기점으로
                  복리 계산해서 생성한다(정확성·Best≥Base≥Worst 보장·지연시간
                  이유 — feature/simulation 설계 논의 결정). value는 종목의
                  주당 시장가(price)가 아니라 investment_amount 복리 계산
                  결과인 "포트폴리오 평가금액" 총액이므로 필드명을 price가
                  아닌 value로 둔다(price는 stock:price:{stockCode} 캐시 등
                  코드베이스 전역에서 이미 "주당가" 의미로 쓰이고 있어 재사용
                  시 혼동 위험).
                  {
                    "best":  [{"date":"2026-05-01","value":8500000}, ...],
                    "base":  [{"date":"2026-05-01","value":7500000}, ...],
                    "worst": [{"date":"2026-05-01","value":6200000}, ...]
                  }
*/
CREATE TABLE simulations (
    simulation_id     BIGINT     NOT NULL AUTO_INCREMENT,
    user_id           BIGINT     NOT NULL,
    stock_code        VARCHAR(10) NOT NULL,
    stock_name        VARCHAR(50) NOT NULL,
    investment_amount BIGINT     NOT NULL,           -- 투자 원금 (원) — 실제 보유 종목/수량과 무관
    target_amount     BIGINT     NOT NULL,            -- 목표 금액 (원)
    target_months     INT        NOT NULL,            -- 목표 기간 (개월)
    scenario_data     JSON       NOT NULL,            -- 서버 복리 계산 시나리오 곡선
    best_reach_date  DATE,                           -- Best 시나리오 목표 도달 예상일
    base_reach_date  DATE,                           -- Base 시나리오 목표 도달 예상일
    worst_reach_date DATE,                           -- Worst 시나리오 목표 도달 예상일
    dart_data        JSON,                           -- Open DART 재무 원본
    news_data        JSON,                           -- 네이버 뉴스 검색 원본
    created_at       DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (simulation_id),
    INDEX idx_user_sim (user_id),
    FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- =====================================================
-- 11. 최근 본 종목 (recent_viewed)
-- =====================================================
/*
  ON UPDATE CURRENT_TIMESTAMP 적용
  → 같은 종목을 다시 보면 새 행 추가 없이 viewed_at만 갱신
*/
CREATE TABLE recent_viewed (
    view_id     BIGINT          NOT NULL AUTO_INCREMENT,
    user_id     BIGINT          NOT NULL,
    stock_code  VARCHAR(10)     NOT NULL,
    stock_name  VARCHAR(50)     NOT NULL,
    viewed_at   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP
                                         ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (view_id),
    UNIQUE KEY uq_user_stock_view (user_id, stock_code),
    FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- =====================================================
-- 12. 알림 (notifications)
-- =====================================================
/*
  [idx_user_noti 복합 인덱스]
  "내 알림 중 안 읽은 것" 조회가 가장 빈번한 쿼리
  → (user_id, is_read) 복합 인덱스 적용
*/
CREATE TABLE notifications (
    noti_id     BIGINT          NOT NULL AUTO_INCREMENT,
    user_id     BIGINT          NOT NULL,
    type        ENUM('SYSTEM','ORDER','AI','SIMULATION','NEWS') NOT NULL,  -- v12: NEWS 추가 (feature/ai-news)
    title       VARCHAR(100)    NOT NULL,
    content     VARCHAR(500)    NOT NULL,
    is_read     TINYINT(1)      NOT NULL DEFAULT 0,
    created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (noti_id),
    INDEX idx_user_noti (user_id, is_read),
    FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- =====================================================
-- 13. 사용자 문의 (inquiries) — v8 신규
-- =====================================================
/*
  [설계 방향 — 단일 테이블로 문의+답변 통합]
  문의:답변 = 1:1 관계이고 프로젝트 규모상 문의당 여러 답변(스레드형 댓글)이
  필요하지 않으므로, 별도 inquiry_answers 테이블 없이 answer 관련 컬럼을
  inquiries 테이블에 함께 둔다. 추후 다중 답변/재문의가 필요해지면 그때
  별도 테이블로 분리한다.

  [status 흐름]
  PENDING  : 사용자가 문의 작성 직후 기본값
  ANSWERED : 관리자가 답변 작성 시 전환 (answer, answered_by, answered_at 동시 기록)

  [answered_by를 ON DELETE SET NULL로 둔 이유]
  answered_by는 답변한 관리자의 user_id를 참조한다. 관리자 계정이
  탈퇴/삭제되어도 문의-답변 기록 자체(질문·답변 내용)는 보존되어야 하므로,
  참조 무결성 훼손 없이 answered_by만 NULL로 비운다(CASCADE 삭제 금지).
  반면 user_id(문의 작성자)는 다른 테이블과 동일하게 ON DELETE CASCADE를
  유지한다 — 탈퇴 시 명시적 삭제 순서는 1번 users 테이블 주석 참고
  (자식 테이블 목록에 inquiries 추가 필요).

  [관리자 페이지 활용]
  GET  /api/admin/inquiries              : 전체 문의 목록 (status 필터 가능)
  GET  /api/admin/inquiries/{inquiryId}  : 문의 상세
  PATCH /api/admin/inquiries/{inquiryId}/answer : 답변 작성 (status → ANSWERED)

  [사용자 페이지 활용]
  POST /api/inquiries              : 문의 작성
  GET  /api/inquiries               : 내 문의 목록 (idx_user_inquiry로 조회)
  GET  /api/inquiries/{inquiryId}  : 내 문의 상세 (답변 포함)
*/
CREATE TABLE inquiries (
    inquiry_id  BIGINT          NOT NULL AUTO_INCREMENT,
    user_id     BIGINT          NOT NULL,             -- 문의 작성자
    title       VARCHAR(200)    NOT NULL,
    content     TEXT            NOT NULL,
    status      ENUM('PENDING','ANSWERED') NOT NULL DEFAULT 'PENDING',
    answer      TEXT            NULL,                 -- 관리자 답변 (미답변=NULL)
    answered_by BIGINT          NULL,                 -- 답변한 관리자 user_id
    answered_at DATETIME        NULL,                 -- 답변 시각 (미답변=NULL)
    created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP
                                         ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (inquiry_id),
    INDEX idx_user_inquiry (user_id, created_at),      -- 사용자 본인 문의 목록 조회용
    INDEX idx_inquiry_status (status),                 -- 관리자 페이지 미답변 필터링용
    FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE,
    FOREIGN KEY (answered_by) REFERENCES users(user_id) ON DELETE SET NULL
) ENGINE=InnoDB;

-- =====================================================
-- 14. 뉴스 브리핑 설정 (news_briefing_settings) — v12 신규
-- =====================================================
/*
  [설계 방향]
  사용자가 고른 언론사 "현재 설정"만 담는다. 언론사는 한 번에 하나만 고를 수 있다
  (2026-08-24 확정 — 한국경제를 고르면 한국경제만, 매일경제로 바꾸면 그때부터
  매일경제만). uq_news_setting_user로 유저당 행 1개만 허용한다.

  [outlet_domain]
  언론사를 도메인 문자열로 저장한다(예: "hankyung.com"). 어느 언론사가 선택
  가능한지는 NewsRelevanceMatcher.OUTLET_NAMES(global/util, 신뢰 매체 29곳 중
  일부)에 코드로 등록돼 있고, GET /api/ai/news/outlets가 이 목록을 그대로 노출한다.

  [news_briefings와의 관계]
  이 테이블은 "현재 설정"만 담고, 실제 생성된 브리핑 결과는 news_briefings에 별도로
  쌓인다 — 설정을 바꿔도 과거에 만들어진 브리핑은 그때 기준 언론사를 그대로
  유지해야 하기 때문이다(news_briefings 테이블 주석 참고).
*/
CREATE TABLE news_briefing_settings (
    setting_id     BIGINT          NOT NULL AUTO_INCREMENT,
    user_id        BIGINT          NOT NULL,
    outlet_domain  VARCHAR(50)     NOT NULL,
    created_at     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP
                                             ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (setting_id),
    UNIQUE KEY uq_news_setting_user (user_id),
    FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- =====================================================
-- 15. 뉴스 브리핑 (news_briefings) — v12 신규
-- =====================================================
/*
  [생성 흐름]
  AiNewsService.generateDailyBriefings()가 매일 07:00(KST)에 news_briefing_settings를
  가진 사용자 전원을 순회한다. NaverNewsApiClient.searchByOutlet(outletDomain)로 그
  언론사의 오늘자 시황 기사를 모으고, Gemini로 3~4문장 요약을 만들어 한 행씩 저장한
  뒤 notifications에도 "오늘의 브리핑 도착" 알림을 함께 남긴다(type='NEWS').
  재무설계사(ai_planning_sessions/messages)처럼 대화 이력을 쌓는 게 아니라, 하루 한
  건의 완성된 요약만 저장하는 구조다.

  [outlet_domain을 설정과 별도로 복제 저장하는 이유]
  news_briefing_settings.outlet_domain은 "지금" 기준이라, 사용자가 언론사를 바꾸면
  과거에 이미 만들어진 브리핑이 실제로는 어느 언론사 기준이었는지 알 수 없게 된다.
  그래서 생성 시점의 언론사를 이 테이블에도 그대로 복사해 기록한다.

  [uq_news_briefing_user_date]
  유저당 하루 한 건만 허용 — 스케줄러가 재시작 등으로 같은 날 두 번 돌아도
  중복 생성되지 않도록 서비스 계층(existsByUserIdAndBriefingDate 선확인)과 DB
  제약 양쪽에서 막는다.

  [source_links]
  요약이 어떤 기사를 근거로 만들어졌는지 [{title, link, outlet}, ...] 형태로 저장한다
  (simulations.scenario_data와 동일하게 JSON 컬럼 + 서비스 계층 직렬화 패턴, 2026-08-24
  사용자 요청). 프론트가 각 기사를 원문 링크로 바로 이동시킬 수 있도록 link를 포함한다.
*/
CREATE TABLE news_briefings (
    briefing_id    BIGINT          NOT NULL AUTO_INCREMENT,
    user_id        BIGINT          NOT NULL,
    outlet_domain  VARCHAR(50)     NOT NULL,
    briefing_date  DATE            NOT NULL,
    content        TEXT            NOT NULL,             -- Gemini 요약 본문
    source_links   JSON            NOT NULL,             -- 요약 근거 기사 [{title,link,outlet}, ...] (원문 이동용)
    created_at     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (briefing_id),
    UNIQUE KEY uq_news_briefing_user_date (user_id, briefing_date),
    INDEX idx_news_briefing_user (user_id, briefing_date),
    FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- =====================================================
-- 16. 충전 요청·승인 (charge_requests) — v13 신규
-- =====================================================
/*
  [용도]
  accounts.charge_count(자동 충전, 3회 한도)를 넘긴 사용자가 관리자 승인을 받아
  추가로 충전하는 절차(ADMIN_API_BACKEND_HANDOFF.md 4.2, 2026-09-07 사용자 승인으로
  신규 생성). ChargeRequest.approve()가 호출되면 accounts.applyAdminCharge()로
  balance/base_balance가 올라가되 charge_count는 올리지 않는다(자동 충전 한도와
  별개 트랙이라는 의미).

  [계좌당 동시 PENDING 1건 제한 — 정책 확정 전 우선 구현]
  handoff 문서가 "일·월 누적 한도 등 세부 정책 필요"로 남긴 항목이라, 우선 "계좌당
  미처리(PENDING) 요청은 동시에 1건만 허용"으로 좁혀 구현했다(NAMING.md 8-25 참고).
  최종 정책이 확정되면 검증 로직만 서비스 계층에서 조정하면 되고 스키마 변경은
  필요 없다.

  [decided_by — nullable FK]
  inquiries.answered_by와 동일한 설계(CLAUDE.md 6번) — 처리한 관리자가 이후 탈퇴해도
  요청 처리 기록 자체는 보존해야 하므로 ON DELETE SET NULL.
*/
CREATE TABLE charge_requests (
    request_id       BIGINT          NOT NULL AUTO_INCREMENT,
    account_id        BIGINT          NOT NULL,
    amount            BIGINT          NOT NULL,
    reason            VARCHAR(500)    NOT NULL,
    status            ENUM('PENDING','APPROVED','REJECTED')
                                      NOT NULL DEFAULT 'PENDING',
    decided_by        BIGINT,                              -- 처리한 관리자 user_id (nullable — 탈퇴 시 SET NULL)
    decision_reason   VARCHAR(500),
    requested_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    decided_at        DATETIME,
    PRIMARY KEY (request_id),
    INDEX idx_charge_request_account (account_id),
    INDEX idx_charge_request_status (status),
    FOREIGN KEY (account_id) REFERENCES accounts(account_id) ON DELETE CASCADE,
    FOREIGN KEY (decided_by) REFERENCES users(user_id) ON DELETE SET NULL
) ENGINE=InnoDB;

-- =====================================================
-- 17. 계좌 잔고 변동 원장 (account_transactions) — v13 신규
-- =====================================================
/*
  [용도]
  계좌 잔고가 바뀌는 모든 지점(최초 지급/자동 충전/관리자 충전·차감/주문 매수·매도/
  지정가 환불)을 append-only로 남기는 장부(ADMIN_API_BACKEND_HANDOFF.md 4.3,
  2026-09-07 사용자 승인으로 신규 생성). 수정·삭제 API가 없다 — 잘못 기록된 값이
  있어도 반대 방향 보정 행을 새로 추가하는 방식으로 처리한다(회계 원장과 동일한 원칙).

  [related_order_id / related_charge_request_id / processed_by — FK 아님]
  주문 체결마다 매번 orders/users를 조인할 필요는 없고 "어느 주문·충전요청 때문에
  생긴 변동인지"만 참조 가능하면 충분하다는 판단으로 단순 ID 컬럼으로 뒀다(FK
  제약을 걸면 원본 주문이 삭제될 일이 없는 이 서비스 특성상 실익이 없다). processed_by는
  ADMIN_CHARGE/ADMIN_DEDUCTION일 때만 채워지는 관리자 user_id.

  [amount / balance_before / balance_after]
  amount는 증감액(양수=증가, 음수=감소)이며 항상 balance_after - balance_before와
  같다. 조회 시 별도 계산 없이 이 세 컬럼만으로 "그 순간 얼마에서 얼마로 바뀌었는지"를
  바로 알 수 있게 하기 위해 셋 다 저장한다.
*/
CREATE TABLE account_transactions (
    transaction_id            BIGINT          NOT NULL AUTO_INCREMENT,
    account_id                BIGINT          NOT NULL,
    type                      ENUM('INITIAL_GRANT','AUTO_CHARGE','ADMIN_CHARGE','ADMIN_DEDUCTION',
                                    'ORDER_BUY','ORDER_SELL','ORDER_REFUND')
                                              NOT NULL,
    amount                    BIGINT          NOT NULL,     -- 증감액 (양수=증가, 음수=감소)
    balance_before             BIGINT          NOT NULL,
    balance_after              BIGINT          NOT NULL,
    related_order_id           BIGINT,                       -- FK 아님, 참조용 (orders.order_id)
    related_charge_request_id  BIGINT,                       -- FK 아님, 참조용 (charge_requests.request_id)
    processed_by               BIGINT,                       -- FK 아님, 참조용 (users.user_id) — 관리자 개입 시에만
    reason                     VARCHAR(500),
    created_at                 DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (transaction_id),
    INDEX idx_account_transaction_account (account_id, created_at),
    FOREIGN KEY (account_id) REFERENCES accounts(account_id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- =====================================================
-- 18. 관리자 작업 감사 로그 (audit_logs) — v13 신규
-- =====================================================
/*
  [용도]
  관리자가 수행한 주요 작업(사용자·계좌 상태 변경, 계좌 잔고 조정, 주문 강제취소,
  충전요청 승인/거절, 관리자 신규 생성)을 append-only로 남긴다(ADMIN_API_BACKEND_HANDOFF.md
  5.2, 2026-09-07 사용자 승인으로 신규 생성). handoff 문서 요구사항대로 관리자도
  수정·삭제할 수 없다 — UPDATE/DELETE API 자체를 만들지 않는다.

  [admin_user_id / admin_login_id — FK 아님 + 스냅샷]
  관리자가 나중에 탈퇴·정지되어도 "그 시점에 누가 했는지"를 그대로 읽을 수 있어야
  하는 감사 로그 특성상, FK로 users를 참조하지 않고 기록 시점의 login_id를 값 그대로
  복사해 저장한다(조회 시점에 조인해서 알아내는 방식이 아님).

  [target_type / target_id]
  ACCOUNT/USER/ORDER/CHARGE_REQUEST 등 문자열로 대상 종류를 구분하고 target_id로
  구체적인 행을 가리킨다 — 감사 로그 한 테이블이 여러 도메인의 행위를 다루므로
  다형적 참조가 필요해 단일 FK 대신 이 방식을 택했다.

  [request_ip]
  최초 구현 시엔 "서비스 메서드 시그니처 변경이 여러 곳에 필요해 범위 밖"으로 컬럼만
  만들고 항상 null이었으나, 코드리뷰 반영 이후 JwtAuthenticationFilter가 인증 시점에
  SecurityContext에 심어둔 WebAuthenticationDetails에서 꺼내 채우도록 바뀌어 실제
  값이 들어간다(NAMING.md 8-27 참고). 인증 컨텍스트가 없는 경로에서는 여전히 null일
  수 있다.
*/
CREATE TABLE audit_logs (
    audit_log_id    BIGINT          NOT NULL AUTO_INCREMENT,
    admin_user_id   BIGINT          NOT NULL,                -- FK 아님, 기록 시점 참조 ID
    admin_login_id  VARCHAR(50)     NOT NULL,                -- 기록 시점 스냅샷 (관리자 탈퇴 후에도 보존)
    action          VARCHAR(50)     NOT NULL,                -- 예: USER_STATUS_CHANGE, ACCOUNT_ADJUSTMENT, ORDER_CANCEL
    target_type     VARCHAR(30)     NOT NULL,                -- 예: USER, ACCOUNT, ORDER, CHARGE_REQUEST
    target_id       BIGINT          NOT NULL,
    before_value    VARCHAR(500),
    after_value     VARCHAR(500),
    reason          VARCHAR(500),
    request_ip      VARCHAR(45),                             -- IPv6까지 고려한 길이
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (audit_log_id),
    INDEX idx_audit_log_admin (admin_user_id),
    INDEX idx_audit_log_target (target_type, target_id),
    INDEX idx_audit_log_created (created_at)
) ENGINE=InnoDB;

-- =====================================================
-- 테이블 관계 요약 (총 18개 — v13: charge_requests, account_transactions, audit_logs 추가)
-- =====================================================
/*
  users 1:1  → investment_profile
  users 1:N  → social_accounts
  users 1:N  → accounts
  users 1:N  → watchlist
  users 1:N  → ai_planning_sessions
  users 1:N  → simulations
  users 1:N  → recent_viewed
  users 1:N  → notifications
  users 1:N  → inquiries (작성자 기준)
  users 1:N  → inquiries (답변자 기준, answered_by — nullable)
  users 1:1  → news_briefing_settings (v12)
  users 1:N  → news_briefings (v12)
  users 1:N  → charge_requests (decided_by 기준, nullable — v13)
  accounts 1:N → holdings
  accounts 1:N → orders
  accounts 1:N → charge_requests (v13)
  accounts 1:N → account_transactions (v13)
  ai_planning_sessions 1:N → ai_planning_messages

  ※ audit_logs는 FK 없이 admin_user_id/target_id를 참조 ID로만 저장한다(위 [admin_user_id
    / admin_login_id — FK 아님 + 스냅샷] 참고) — 다른 테이블과 관계선을 긋지 않는다.
*/

-- =====================================================
-- 총 자산 계산 참고 (서버 코드)
-- =====================================================
/*
  총 현금  = accounts.balance + accounts.frozen_balance
  주식평가 = Σ (holdings.quantity × Redis stock:price:{stockCode})
  총 자산  = 총 현금 + 주식평가
  수익률   = (총 자산 - accounts.base_balance) / accounts.base_balance × 100
  (계좌 단위 계산 — 유저가 계좌를 여러 개 가진 경우 계좌별로 각각 계산한다)
*/

-- =====================================================
-- 관리자 페이지 참고 쿼리 (v7 추가 — 서버 코드 예시, DDL 아님)
-- =====================================================
/*
  [대시보드 — 총 사용자 수]
  SELECT COUNT(*) FROM users WHERE is_active = 1;

  [대시보드 — 총 거래량 (실행된 주문 기준)]
  SELECT COUNT(*) AS trade_count, SUM(exec_price * quantity) AS trade_amount
  FROM orders WHERE status = 'EXECUTED';

  [대시보드 — 최근 거래 N건]
  SELECT o.*, u.name, u.login_id
  FROM orders o
  JOIN accounts a ON a.account_id = o.account_id
  JOIN users u ON u.user_id = a.user_id
  WHERE o.status = 'EXECUTED'
  ORDER BY o.executed_at DESC
  LIMIT 20;

  ※ "온라인 사용자 수"는 DB가 아니라 Redis admin:online:users(Set)의
    SCARD로 조회한다 (redis-logic.md 참고).

  [사용자 관리 — 상세정보 조회 (기본정보+자산현황+보유종목+거래내역)]
  기본정보 : SELECT * FROM users WHERE user_id = ?;
  자산현황 : SELECT * FROM accounts WHERE user_id = ?;
  보유종목 : SELECT h.* FROM holdings h
             JOIN accounts a ON a.account_id = h.account_id
             WHERE a.user_id = ?;
  거래내역 : SELECT o.* FROM orders o
             JOIN accounts a ON a.account_id = o.account_id
             WHERE a.user_id = ? ORDER BY o.ordered_at DESC;

  [사용자 관리 — 활성 상태 변경]
  UPDATE users SET status = ? WHERE user_id = ?;   -- 'ACTIVE' or 'SUSPENDED'

  [계좌 관리 — 기능 정지/해제]
  UPDATE accounts SET status = ? WHERE account_id = ?;   -- 'ACTIVE' or 'SUSPENDED'

  [문의 관리 — 전체 목록 조회 (v8 추가, 미답변 우선)]
  SELECT i.*, u.name, u.login_id
  FROM inquiries i
  JOIN users u ON u.user_id = i.user_id
  ORDER BY i.status DESC, i.created_at DESC;  -- 'PENDING'이 'ANSWERED'보다 사전순 뒤(P > A)이므로
                                               -- status를 내림차순 정렬해야 미답변이 위로 옴

  [문의 관리 — 답변 작성]
  UPDATE inquiries
  SET answer = ?, answered_by = ?, answered_at = NOW(), status = 'ANSWERED'
  WHERE inquiry_id = ?;
*/
