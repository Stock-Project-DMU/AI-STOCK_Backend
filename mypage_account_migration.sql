-- =====================================================
-- 마이페이지 계좌 정보 보완 마이그레이션 (schema.sql v17, feature/mypage-improvement, 2026-10-01)
-- =====================================================
-- 운영 DB(ddl-auto=validate) 배포 전에 1회 적용한다. 개발 환경은 Hibernate ddl-auto=update가
-- 1~3번 컬럼/ENUM을 자동 반영하지만, 4번(기존 계좌번호 변환)은 데이터 변경이라 자동 반영되지 않는다.
--
--   1. accounts.interest_rate      — 예치금 연이율(%) 0.50 고정. 기존 계좌도 DEFAULT로 0.50이 채워진다.
--   2. orders.fee                  — 매도 체결 거래 수수료(체결금액 × 0.1%, 원 단위 미만 버림). 기존 주문은 0.
--   3. account_transactions.type   — 'INTEREST'(예치금 이자), 'TRADE_FEE'(매도 거래 수수료) 추가.
--   4. accounts.account_number     — "VA..." 형식을 "110" + 랜덤 9자리 숫자(12자리, 하이픈 없이 저장)로 변환.
--   5. accounts.total_interest     — 지급받은 예치금 이자 누계(원). 기존 INTEREST 원장 합계로 채운다.
--
-- 1~3, 5는 재실행해도 안전하다. 4는 이미 새 형식인 계좌는 건너뛴다.
-- 4에서 랜덤 번호가 기존 번호와 겹치면 UNIQUE 제약 오류로 그 UPDATE 전체가 롤백되니,
-- 그 경우 4번 UPDATE만 다시 실행하면 된다(1억 분의 1 수준 확률).
USE aistock;

-- 1. accounts.interest_rate
SET @has_interest_rate = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'accounts'
      AND COLUMN_NAME = 'interest_rate'
);
SET @add_interest_rate_sql = IF(@has_interest_rate = 0,
    'ALTER TABLE accounts ADD COLUMN interest_rate DECIMAL(5,2) NOT NULL DEFAULT 0.50 AFTER charge_count',
    'SELECT 1');
PREPARE stmt FROM @add_interest_rate_sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2. orders.fee
SET @has_order_fee = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'orders'
      AND COLUMN_NAME = 'fee'
);
SET @add_order_fee_sql = IF(@has_order_fee = 0,
    'ALTER TABLE orders ADD COLUMN fee BIGINT NOT NULL DEFAULT 0 AFTER quantity',
    'SELECT 1');
PREPARE stmt FROM @add_order_fee_sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 3. account_transactions.type ENUM 확장 (MODIFY는 재실행해도 결과가 같다)
ALTER TABLE account_transactions
    MODIFY COLUMN type ENUM('INITIAL_GRANT','AUTO_CHARGE','ADMIN_CHARGE','ADMIN_DEDUCTION',
                            'ORDER_BUY','ORDER_SELL','ORDER_REFUND','INTEREST','TRADE_FEE') NOT NULL;

-- 4. 기존 계좌번호를 110 + 랜덤 9자리로 변환
UPDATE accounts
SET account_number = CONCAT('110', LPAD(FLOOR(RAND() * 1000000000), 9, '0'))
WHERE account_number NOT REGEXP '^110[0-9]{9}$';

-- 5. accounts.total_interest (+ 기존 이자 원장 합계로 채우기)
SET @has_total_interest = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'accounts'
      AND COLUMN_NAME = 'total_interest'
);
SET @add_total_interest_sql = IF(@has_total_interest = 0,
    'ALTER TABLE accounts ADD COLUMN total_interest BIGINT NOT NULL DEFAULT 0 AFTER interest_rate',
    'SELECT 1');
PREPARE stmt FROM @add_total_interest_sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE accounts a
SET a.total_interest = (
    SELECT COALESCE(SUM(t.amount), 0) FROM account_transactions t
    WHERE t.account_id = a.account_id AND t.type = 'INTEREST'
);
