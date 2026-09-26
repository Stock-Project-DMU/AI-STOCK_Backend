-- 기존 news_briefing_settings를 사용하는 운영 DB에 한 번 적용한다.
-- 기존 사용자의 수신 시간은 종전 스케줄과 같은 07:00(KST)으로 유지한다.
USE aistock;

ALTER TABLE news_briefing_settings
    ADD COLUMN delivery_time TIME NOT NULL DEFAULT '07:00:00',
    ADD COLUMN last_attempt_date DATE NULL,
    ADD COLUMN last_attempt_at DATETIME NULL;
