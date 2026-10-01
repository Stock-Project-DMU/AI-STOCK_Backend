-- Run against the application database when ai_planning_messages.content is TINYTEXT.
-- Matches AiPlanningMessage and schema.sql; preserves existing message content.
-- MySQL ALTER TABLE commits implicitly. Apply outside an application transaction.
ALTER TABLE ai_planning_messages
    MODIFY COLUMN content TEXT NOT NULL;

-- Verify the resulting type and capacity.
SELECT COLUMN_TYPE, CHARACTER_MAXIMUM_LENGTH
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'ai_planning_messages'
  AND COLUMN_NAME = 'content';
