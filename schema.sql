-- =====================================================
-- AI STOCK MySQL Schema (최종본 v6)
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

-- =====================================================
-- 1. 회원 (users)
-- =====================================================
/*
  [탈퇴 처리 전략 — 정정]
  주의: FK의 ON DELETE CASCADE는 부모 행(users)이 실제 DELETE될 때만 발동한다.
  탈퇴 처리는 UPDATE users SET deleted_at = NOW() ... 형태의 soft delete이므로,
  자식 테이블(social_accounts, investment_profile, accounts, watchlist,
  ai_planning_sessions, simulations, recent_viewed, notifications)은
  자동으로 삭제되지 않는다. 따라서 탈퇴 서비스 로직에서 아래 순서로
  명시적 삭제를 수행해야 한다.

  1) 자식 테이블 명시적 DELETE (서비스 코드에서 각 Repository의
     deleteByUserId(userId) 등을 통해 수행)
       DELETE FROM social_accounts        WHERE user_id = ?;
       DELETE FROM investment_profile     WHERE user_id = ?;
       DELETE FROM watchlist              WHERE user_id = ?;
       DELETE FROM ai_planning_sessions   WHERE user_id = ?;  -- messages는 FK CASCADE로 자동 삭제
       DELETE FROM simulations            WHERE user_id = ?;
       DELETE FROM recent_viewed          WHERE user_id = ?;
       DELETE FROM notifications          WHERE user_id = ?;
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
    is_active   TINYINT(1)      NOT NULL DEFAULT 1,  -- 1=활성, 0=탈퇴
    deleted_at  DATETIME        NULL,                -- 탈퇴 시각 (활성 회원은 NULL)
    created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP
                                         ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id),
    INDEX idx_email  (email),
    INDEX idx_active (is_active)
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
  [총 자산 계산 (DB 컬럼 없음 — 실시간 계산)]
  총 자산 = (balance + frozen_balance)
            + Σ(holdings.quantity × Redis stock:price:{stockCode})
  수익률 = (총 자산 - initial_balance) / initial_balance × 100

  [잔고 구조]
  initial_balance : 최초 지급 금액 (고정, 수익률 계산 기준)
  balance         : 즉시 사용 가능한 현금 잔고
  frozen_balance  : 지정가 주문 예약 잠금 금액 (balance에서 이미 차감)

  frozen_balance를 accounts에 두는 이유:
  → 잔고 상태를 한 행에서 즉시 파악 가능
  → orders 테이블에 분산하면 매번 SUM 쿼리 필요

  [frozen_balance 처리 흐름]
  지정가 매수 등록: balance -= 주문금액, frozen_balance += 주문금액
  지정가 체결:     frozen_balance -= 주문금액 (balance는 이미 차감 완료)
  지정가 취소:     balance += 주문금액,       frozen_balance -= 주문금액

  [version 컬럼 - 낙관적 락]
  동시성 문제 해결: 지정가 주문 동시 체결 시 잔고 오류 방지
  UPDATE accounts
  SET balance = ?, frozen_balance = ?, version = version + 1
  WHERE account_id = ? AND version = ?
  → 충돌 감지 시 재시도 (모의투자 특성상 충돌 빈도 낮음)
*/
CREATE TABLE accounts (
    account_id      BIGINT          NOT NULL AUTO_INCREMENT,
    user_id         BIGINT          NOT NULL,
    account_number  VARCHAR(20)     NOT NULL UNIQUE,
    opened_at       DATE            NOT NULL,
    base_balance BIGINT          NOT NULL DEFAULT 10000000, -- 지급 금액 (수익률 기준)
    balance         BIGINT          NOT NULL DEFAULT 10000000, -- 즉시 사용 가능한 현금 잔고
    frozen_balance  BIGINT          NOT NULL DEFAULT 0,        -- 지정가 주문 예약 잠금 금액
    version         BIGINT          NOT NULL DEFAULT 0,        -- 낙관적 락용 버전 번호
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (account_id),
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
  news_data     : Tavily API (뉴스, 애널리스트 리포트)
  scenario_data : Gemini API 생성
                  {
                    "best":  [{"date":"2026-05","price":85000}, ...],
                    "base":  [{"date":"2026-05","price":75000}, ...],
                    "worst": [{"date":"2026-05","price":62000}, ...]
                  }
*/
CREATE TABLE simulations (
    simulation_id    BIGINT     NOT NULL AUTO_INCREMENT,
    user_id          BIGINT     NOT NULL,
    stock_code       VARCHAR(10) NOT NULL,
    stock_name       VARCHAR(50) NOT NULL,
    target_amount    BIGINT     NOT NULL,            -- 목표 금액 (원)
    target_months    INT        NOT NULL,            -- 목표 기간 (개월)
    scenario_data    JSON       NOT NULL,            -- Gemini 시나리오 예측값
    best_reach_date  DATE,                           -- Best 시나리오 목표 도달 예상일
    base_reach_date  DATE,                           -- Base 시나리오 목표 도달 예상일
    worst_reach_date DATE,                           -- Worst 시나리오 목표 도달 예상일
    dart_data        JSON,                           -- Open DART 재무 원본
    news_data        JSON,                           -- Tavily 뉴스 원본
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
    type        ENUM('SYSTEM','ORDER','AI','SIMULATION') NOT NULL,
    title       VARCHAR(100)    NOT NULL,
    content     VARCHAR(500)    NOT NULL,
    is_read     TINYINT(1)      NOT NULL DEFAULT 0,
    created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (noti_id),
    INDEX idx_user_noti (user_id, is_read),
    FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- =====================================================
-- 테이블 관계 요약 (총 12개)
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
  accounts 1:N → holdings
  accounts 1:N → orders
  ai_planning_sessions 1:N → ai_planning_messages
*/

-- =====================================================
-- 총 자산 계산 참고 (서버 코드)
-- =====================================================
/*
  총 현금  = accounts.balance + accounts.frozen_balance
  주식평가 = Σ (holdings.quantity × Redis stock:price:{stockCode})
  총 자산  = 총 현금 + 주식평가
  수익률   = (총 자산 - initial_balance) / initial_balance × 100
*/
