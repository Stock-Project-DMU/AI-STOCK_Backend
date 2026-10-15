-- =====================================================
-- 관리자 페이지 개선 마이그레이션 (schema.sql v18, feat/admin-improvements, 2026-10-04)
-- =====================================================
-- 운영 DB(ddl-auto=validate) 배포 전에 1회 적용한다. 전부 재실행해도 안전하다
-- (1은 MODIFY라 결과가 같고, 2~5는 인덱스가, 6·8은 컬럼이 이미 있으면, 7은 테이블이 이미 있으면 건너뛴다).
--
--   1. account_transactions.type — 'AUTO_DEDUCTION'(관리자 계정 본인 계좌 직접 차감) 추가.
--      셀프 충전(AUTO_CHARGE)/관리자 지급(ADMIN_CHARGE)처럼, 직접 차감(AUTO_DEDUCTION)과
--      관리자가 사용자 계좌에서 빼는 차감(ADMIN_DEDUCTION)을 계좌 내역에서 구분하기 위해서다.
--   2~4. users.created_at / orders.ordered_at / charge_requests.requested_at 인덱스 — 관리자
--      "전체 활동 기록"(GET /api/admin/activities)이 4개 테이블을 발생 시각 최신순으로 합쳐
--      페이지 단위로 조회한다(audit_logs.created_at은 기존 idx_audit_log_created 사용).
--   5. account_transactions(type, created_at) 인덱스 — 관리자 "충전·차감 이력"(GET /api/admin/account-transactions)이
--      전체 계좌에서 충전·차감 유형만 골라 최신순으로 조회한다.
--   6. users.last_login_at — 마지막 로그인 시각(관리자 회원 상세 표시용). 기존 회원은 다음 로그인 때부터 채워진다.
--   7. notices 테이블 신규 — 관리자가 보낸 공지 한 건(관리자 "알림 관리"). 팝업 공지는 popup_end_date(포함)까지 받은
--      회원이 로그인할 때마다 팝업으로 뜬다(닫은 기록은 남기지 않음).
--   8. notifications.notice_id — 공지로 받은 알림이 그 공지를 가리킨다(공지 삭제 시 함께 삭제). 기존 알림은 NULL.
USE aistock;

-- 1. account_transactions.type ENUM 확장
ALTER TABLE account_transactions
    MODIFY COLUMN type ENUM('INITIAL_GRANT','AUTO_CHARGE','ADMIN_CHARGE','ADMIN_DEDUCTION',
                            'ORDER_BUY','ORDER_SELL','ORDER_REFUND','INTEREST','TRADE_FEE',
                            'AUTO_DEDUCTION') NOT NULL;

-- 2. users.created_at 인덱스
SET @has_user_created_idx = (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users'
      AND INDEX_NAME = 'idx_user_created_at'
);
SET @add_user_created_idx_sql = IF(@has_user_created_idx = 0,
    'CREATE INDEX idx_user_created_at ON users (created_at)',
    'SELECT 1');
PREPARE stmt FROM @add_user_created_idx_sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 3. orders.ordered_at 인덱스
SET @has_order_ordered_idx = (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'orders'
      AND INDEX_NAME = 'idx_order_ordered_at'
);
SET @add_order_ordered_idx_sql = IF(@has_order_ordered_idx = 0,
    'CREATE INDEX idx_order_ordered_at ON orders (ordered_at)',
    'SELECT 1');
PREPARE stmt FROM @add_order_ordered_idx_sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 4. charge_requests.requested_at 인덱스
SET @has_charge_requested_idx = (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'charge_requests'
      AND INDEX_NAME = 'idx_charge_request_requested_at'
);
SET @add_charge_requested_idx_sql = IF(@has_charge_requested_idx = 0,
    'CREATE INDEX idx_charge_request_requested_at ON charge_requests (requested_at)',
    'SELECT 1');
PREPARE stmt FROM @add_charge_requested_idx_sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 5. account_transactions(type, created_at) 인덱스
SET @has_tx_type_created_idx = (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'account_transactions'
      AND INDEX_NAME = 'idx_account_transaction_type_created'
);
SET @add_tx_type_created_idx_sql = IF(@has_tx_type_created_idx = 0,
    'CREATE INDEX idx_account_transaction_type_created ON account_transactions (type, created_at)',
    'SELECT 1');
PREPARE stmt FROM @add_tx_type_created_idx_sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 6. users.last_login_at
SET @has_last_login_at = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users'
      AND COLUMN_NAME = 'last_login_at'
);
SET @add_last_login_at_sql = IF(@has_last_login_at = 0,
    'ALTER TABLE users ADD COLUMN last_login_at DATETIME(6) NULL',
    'SELECT 1');
PREPARE stmt FROM @add_last_login_at_sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 7. notices
CREATE TABLE IF NOT EXISTS notices (
    notice_id           BIGINT          NOT NULL AUTO_INCREMENT,
    type                ENUM('SYSTEM','ORDER','AI','SIMULATION','NEWS','ACCOUNT') NOT NULL,
    title               VARCHAR(100)    NOT NULL,
    content             VARCHAR(500)    NOT NULL,
    target_type         ENUM('SINGLE','ALL','SEARCH','SELECTED') NOT NULL,
    target_count        INT             NOT NULL DEFAULT 0,
    sent_count          INT             NOT NULL DEFAULT 0,
    popup_end_date      DATE            NULL,
    created_by          BIGINT          NULL,
    created_by_login_id VARCHAR(50)     NULL,
    created_at          DATETIME(6)     NOT NULL,
    updated_at          DATETIME(6)     NOT NULL,
    PRIMARY KEY (notice_id),
    INDEX idx_notice_created_at (created_at),
    CONSTRAINT fk_notice_created_by FOREIGN KEY (created_by) REFERENCES users(user_id) ON DELETE SET NULL
) ENGINE=InnoDB;

-- 8. notifications.notice_id (+ 인덱스, FK)
SET @has_noti_notice_id = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'notifications'
      AND COLUMN_NAME = 'notice_id'
);
SET @add_noti_notice_id_sql = IF(@has_noti_notice_id = 0,
    'ALTER TABLE notifications ADD COLUMN notice_id BIGINT NULL, ADD INDEX idx_noti_notice (notice_id), ADD CONSTRAINT fk_notification_notice FOREIGN KEY (notice_id) REFERENCES notices(notice_id) ON DELETE CASCADE',
    'SELECT 1');
PREPARE stmt FROM @add_noti_notice_id_sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
