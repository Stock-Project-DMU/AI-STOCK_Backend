-- =====================================================
-- 사이트 다듬기 마이그레이션 (schema.sql v22, fix/site-polish, 2026-10-29)
-- =====================================================
-- 운영 DB(ddl-auto=validate) 배포 전에 1회 적용한다. 개발 DB(ddl-auto=update)는 1번 컬럼은 자동으로 추가하지만
-- 2번(기존 UNIQUE 제약 삭제)은 반영하지 않으므로 개발 DB에도 직접 적용해야 한다 — 안 하면 낮에 브리핑 설정을 바꾼 사용자의
-- 새 설정 브리핑 저장이 예전 제약(사용자+날짜)에 걸려 실패한다.
--
--   1. accounts.suspension_reason — 관리자가 계좌 거래를 정지할 때 남긴 사유(주문·충전 차단 안내 문구에 함께 보여준다)
--   2. news_briefings UNIQUE (user_id, briefing_date) → (user_id, briefing_date, outlet_domain, briefing_time)
--      — 브리핑 설정(언론사+시각)마다 하루 1건. 설정을 바꾸면 그 설정 세션의 브리핑을 새로 만든다.
--   3. news_chat_sessions.deleted_at — 사용자가 삭제한 설정별 채팅(목록에서 빼고 sync가 다시 만들지 않게 표시만 남긴다)
--
-- 전부 재실행해도 안전하다(이미 적용된 부분은 information_schema로 확인해 건너뛴다).
USE aistock;

-- 1. accounts.suspension_reason
SET @has_account_suspension_reason = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'accounts'
      AND COLUMN_NAME = 'suspension_reason'
);
SET @add_account_suspension_reason_sql = IF(@has_account_suspension_reason = 0,
    'ALTER TABLE accounts ADD COLUMN suspension_reason VARCHAR(500) NULL AFTER status',
    'SELECT 1');
PREPARE stmt FROM @add_account_suspension_reason_sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2-1. 예전 UNIQUE(사용자+날짜) 삭제 — user_id 외래키는 idx_news_briefing_user(user_id, briefing_date) 인덱스가 계속 받친다.
SET @has_old_briefing_unique = (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'news_briefings'
      AND INDEX_NAME = 'uq_news_briefing_user_date'
);
SET @drop_old_briefing_unique_sql = IF(@has_old_briefing_unique > 0,
    'ALTER TABLE news_briefings DROP INDEX uq_news_briefing_user_date',
    'SELECT 1');
PREPARE stmt FROM @drop_old_briefing_unique_sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2-2. 새 UNIQUE(사용자+날짜+언론사+시각). briefing_time이 NULL인 예전 행끼리는 MySQL UNIQUE가 중복으로 보지 않는다.
SET @has_new_briefing_unique = (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'news_briefings'
      AND INDEX_NAME = 'uq_news_briefing_user_date_setting'
);
SET @add_new_briefing_unique_sql = IF(@has_new_briefing_unique = 0,
    'ALTER TABLE news_briefings ADD UNIQUE KEY uq_news_briefing_user_date_setting (user_id, briefing_date, outlet_domain, briefing_time)',
    'SELECT 1');
PREPARE stmt FROM @add_new_briefing_unique_sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 3. news_chat_sessions.deleted_at
SET @has_news_chat_deleted_at = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'news_chat_sessions'
      AND COLUMN_NAME = 'deleted_at'
);
SET @add_news_chat_deleted_at_sql = IF(@has_news_chat_deleted_at = 0,
    'ALTER TABLE news_chat_sessions ADD COLUMN deleted_at DATETIME NULL AFTER updated_at',
    'SELECT 1');
PREPARE stmt FROM @add_news_chat_deleted_at_sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
