-- 기존 알림은 주문 번호 없이 유지하고, 새 주문 알림에만 연결 정보를 저장한다.
USE aistock;

ALTER TABLE notifications
    MODIFY COLUMN type ENUM('SYSTEM','ORDER','AI','SIMULATION','NEWS','ACCOUNT') NOT NULL;

SET @has_related_order_id = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'notifications'
      AND COLUMN_NAME = 'related_order_id'
);
SET @add_related_order_id_sql = IF(@has_related_order_id = 0,
    'ALTER TABLE notifications ADD COLUMN related_order_id BIGINT NULL',
    'SELECT 1');
PREPARE stmt FROM @add_related_order_id_sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
