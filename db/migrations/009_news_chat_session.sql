-- Apply before deploying the news chat session code in environments using
-- spring.jpa.hibernate.ddl-auto=validate. Existing briefing times are unknown
-- and deliberately remain NULL; the UI keeps them in an archive branch.
USE aistock;

SET @has_briefing_time = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'news_briefings'
      AND COLUMN_NAME = 'briefing_time'
);
SET @add_briefing_time_sql = IF(@has_briefing_time = 0,
    'ALTER TABLE news_briefings ADD COLUMN briefing_time TIME NULL AFTER outlet_domain',
    'SELECT 1');
PREPARE stmt FROM @add_briefing_time_sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS news_chat_sessions (
    session_id     BIGINT      NOT NULL AUTO_INCREMENT,
    user_id        BIGINT      NOT NULL,
    setting_key    VARCHAR(80) NOT NULL,
    outlet_domain  VARCHAR(50) NULL,
    delivery_time  TIME        NULL,
    created_at     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (session_id),
    UNIQUE KEY uq_news_chat_user_setting (user_id, setting_key),
    INDEX idx_news_chat_user_updated (user_id, updated_at),
    FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS news_chat_messages (
    message_id   BIGINT      NOT NULL AUTO_INCREMENT,
    session_id   BIGINT      NOT NULL,
    role         VARCHAR(10) NOT NULL,
    content      TEXT        NOT NULL,
    sources_json JSON        NOT NULL,
    searched_at  VARCHAR(40) NULL,
    created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (message_id),
    INDEX idx_news_chat_message_session (session_id, message_id),
    FOREIGN KEY (session_id) REFERENCES news_chat_sessions(session_id) ON DELETE CASCADE
) ENGINE=InnoDB;
