-- =====================================================
-- AI STOCK MySQL Schema (최종본 v8)
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
       DELETE FROM inquiries              WHERE user_id = ?;  -- v8 추가 (answered_by로 참조된 다른 문의는 영향 없음)
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
    account_number  VARCHAR(20)     NOT NULL UNIQUE,
    opened_at       DATE            NOT NULL,
    base_balance BIGINT          NOT NULL DEFAULT 10000000, -- 지급 금액 (수익률 기준)
    balance         BIGINT          NOT NULL DEFAULT 10000000, -- 즉시 사용 가능한 현금 잔고
    frozen_balance  BIGINT          NOT NULL DEFAULT 0,        -- 지정가 주문 예약 잠금 금액
    version         BIGINT          NOT NULL DEFAULT 0,        -- 낙관적 락용 버전 번호
    status          ENUM('ACTIVE','SUSPENDED')
                                    NOT NULL DEFAULT 'ACTIVE', -- v7 추가: 관리자에 의한 거래 정지
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (account_id),
    INDEX idx_account_status (status),               -- v7 추가: 관리자 페이지 정지 계좌 필터링용
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
-- 테이블 관계 요약 (총 13개 — v8: inquiries 추가)
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
