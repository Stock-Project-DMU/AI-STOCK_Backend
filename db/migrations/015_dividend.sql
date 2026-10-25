-- =====================================================
-- 배당금 기능 마이그레이션 (schema.sql v19, feature/dividend, 2026-10-05)
-- =====================================================
-- 운영 DB(ddl-auto=validate) 배포 전에 1회 적용한다. 개발 환경은 Hibernate ddl-auto=update가 1·2번
-- 테이블을 자동 생성하지만, 3번(기존 ENUM 컬럼 값 추가)은 ddl-auto=update가 반영하지 않으므로
-- 개발 DB에도 3번은 직접 적용해야 한다(안 하면 배당 지급 시 원장 INSERT가 "Data truncated"로 실패).
--
--   1. dividend_schedules       — 종목별 배당 회차(local-market-data-generator의 dividends.json을 UPSERT)
--   2. dividend_entitlements    — 배당락일 기준 보유 수량으로 확정된 계좌별 배당 권리(PENDING/PAID/SKIPPED)
--   3. account_transactions.type — 'DIVIDEND'(현금배당 입금) 추가 (v18의 'AUTO_DEDUCTION' 유지)
--
-- 전부 재실행해도 안전하다(CREATE TABLE IF NOT EXISTS, MODIFY는 결과가 같음).
USE aistock;

-- 1. 배당 스케줄
CREATE TABLE IF NOT EXISTS dividend_schedules (
    dividend_schedule_id  BIGINT          NOT NULL AUTO_INCREMENT,
    stock_code            VARCHAR(10)     NOT NULL,
    fiscal_year           VARCHAR(4)      NOT NULL,
    period                VARCHAR(10)     NOT NULL,     -- Q1~Q4 (분기 결산기준일 기준 회차)
    dividend_kind         VARCHAR(20),                  -- ANNUAL / QUARTERLY
    dps_cash              INT,                          -- 주당 현금배당금(원), 공시 전이면 NULL
    record_date           DATE,                         -- 배당 기준일
    ex_dividend_date      DATE,                         -- 배당락일(기준일 직전 영업일)
    pay_date              DATE,                         -- 지급일, 공시 전이면 NULL(기준일 + 45일로 대체)
    dividend_yield        DECIMAL(5,2),
    source                VARCHAR(50),
    created_at            DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (dividend_schedule_id),
    UNIQUE KEY uq_dividend_schedule (stock_code, fiscal_year, period),
    INDEX idx_dividend_schedule_ex_date (ex_dividend_date)
) ENGINE=InnoDB;

-- 2. 배당 권리 (배당락일에 보유한 계좌별 기록)
--    account_id는 FK가 아닌 단순 참조 ID다(account_transactions.related_order_id와 같은 방식) — 회원
--    탈퇴로 계좌가 지워져도 막히지 않고, 지급 시점에 계좌가 없으면 SKIPPED로 닫는다.
CREATE TABLE IF NOT EXISTS dividend_entitlements (
    dividend_entitlement_id  BIGINT       NOT NULL AUTO_INCREMENT,
    account_id               BIGINT       NOT NULL,
    dividend_schedule_id     BIGINT       NOT NULL,
    stock_code               VARCHAR(10)  NOT NULL,
    quantity                 INT          NOT NULL,     -- 배당락일 기준 보유 수량
    dps_cash                 INT          NOT NULL,
    total_amount             BIGINT       NOT NULL,     -- quantity × dps_cash (INT 범위 초과 가능해 BIGINT)
    status                   ENUM('PENDING','PAID','SKIPPED') NOT NULL DEFAULT 'PENDING',
    ex_dividend_date         DATE         NOT NULL,
    pay_date                 DATE,                      -- 권리 생성 시점 공시 지급일, NULL이면 대체 지급일 적용
    paid_at                  DATETIME,
    created_at               DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (dividend_entitlement_id),
    UNIQUE KEY uq_dividend_entitlement (account_id, dividend_schedule_id),
    INDEX idx_dividend_entitlement_status (status),
    INDEX idx_dividend_entitlement_account (account_id, status),
    FOREIGN KEY (dividend_schedule_id) REFERENCES dividend_schedules(dividend_schedule_id)
) ENGINE=InnoDB;

-- 3. account_transactions.type ENUM 확장 (MODIFY는 재실행해도 결과가 같다)
-- admin_improvements_migration.sql(v18)이 추가한 'AUTO_DEDUCTION'을 반드시 함께 적는다 — 빠뜨리면 이 MODIFY가
-- 그 값을 지워, 이미 AUTO_DEDUCTION 행이 있으면 실패하거나 값이 손실된다. v18 마이그레이션을 먼저 적용한 뒤 실행한다.
ALTER TABLE account_transactions
    MODIFY COLUMN type ENUM('INITIAL_GRANT','AUTO_CHARGE','ADMIN_CHARGE','ADMIN_DEDUCTION',
                            'ORDER_BUY','ORDER_SELL','ORDER_REFUND','INTEREST','TRADE_FEE',
                            'AUTO_DEDUCTION','DIVIDEND') NOT NULL;
