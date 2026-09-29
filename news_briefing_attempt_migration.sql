-- 기존 news_briefing_settings에 최근 브리핑 시도 시각을 저장한다.
-- briefing_time은 news_briefing_migration.sql에서 관리한다.
USE aistock;

SET @has_last_attempt_date = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'news_briefing_settings'
      AND COLUMN_NAME = 'last_attempt_date'
);
SET @add_attempt_date_sql = IF(@has_last_attempt_date = 0,
    'ALTER TABLE news_briefing_settings ADD COLUMN last_attempt_date DATE NULL',
    'SELECT 1');
PREPARE stmt FROM @add_attempt_date_sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_last_attempt_at = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'news_briefing_settings'
      AND COLUMN_NAME = 'last_attempt_at'
);
SET @add_attempt_at_sql = IF(@has_last_attempt_at = 0,
    'ALTER TABLE news_briefing_settings ADD COLUMN last_attempt_at DATETIME NULL',
    'SELECT 1');
PREPARE stmt FROM @add_attempt_at_sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
