-- 기존 aistock DB에 필요한 신규 테이블만 추가합니다.
-- 전체 schema.sql은 기존 테이블 정의를 포함하므로 재실행하지 마세요.
USE aistock;

CREATE TABLE IF NOT EXISTS planning_preferences (
    user_id BIGINT NOT NULL PRIMARY KEY,
    version BIGINT,
    selections TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS goal_plans (
    plan_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    goal VARCHAR(20) NOT NULL,
    monthly_payment BIGINT NOT NULL,
    years INT NOT NULL,
    annual_return DOUBLE NOT NULL,
    aggressive BOOLEAN NOT NULL,
    is_saved BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL,
    INDEX idx_goal_plan_user (user_id)
);
