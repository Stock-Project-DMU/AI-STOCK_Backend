-- =====================================================
-- feature/ai-news — news_briefing_settings, news_briefings 테이블 신규 생성
-- + notifications.type ENUM에 'NEWS' 추가 보정 스크립트
-- 작성일: 2026-08-24
-- =====================================================
--
-- 배경: 맞춤형 뉴스 브리핑(사용자가 고른 언론사를 매일 요약해 알려주는 기능)을 위해
-- news_briefing_settings(사용자 언론사 설정)/news_briefings(생성된 브리핑 결과) 두
-- 테이블을 추가하고, 브리핑 생성 시 notifications에도 함께 알림을 남기기 위해
-- notifications.type ENUM에 'NEWS' 값을 추가했다. schema.sql v11 → v12 참고.
--
-- 문제: application-prod.yml은 spring.jpa.hibernate.ddl-auto=validate라, Hibernate가
-- 스키마를 자동으로 만들거나 고쳐주지 않는다. 새 테이블/ENUM 값이 반영되지 않은
-- 상태로 배포하면 애플리케이션 기동 자체가 검증 실패로 죽는다(신규 테이블) 또는
-- 'NEWS' 알림 저장 시 "Data truncated for column 'type'" 오류가 난다(ENUM). 배포 전에
-- 이 스크립트로 운영 DB에 먼저 반영해야 한다.
--
-- 적용 대상: notifications 테이블이 이미 생성돼 있는 모든 환경(로컬/개발/운영 공통).
-- application-dev.yml은 ddl-auto=update라 볼륨을 새로 만들어 처음 띄우는 로컬 DB라면
-- Hibernate가 news_briefing_settings/news_briefings를 알아서 만들어주므로 이
-- 스크립트를 실행할 필요가 없다(docker compose down -v && docker compose up -d로
-- 재생성). 다만 notifications.type은 Hibernate가 @Enumerated(STRING)+@Column(length=15)로
-- 매핑해 애초에 네이티브 MySQL ENUM이 아니라 VARCHAR(15)로 만들어지므로, 로컬 dev에서는
-- 'NEWS'(4자)가 그대로 들어가 이 부분은 로컬에서 문제가 되지 않는다 — 아래 ALTER는
-- schema.sql이 네이티브 ENUM으로 정의한 운영 환경 기준 보정이다.

USE aistock;

CREATE TABLE IF NOT EXISTS news_briefing_settings (
    setting_id     BIGINT          NOT NULL AUTO_INCREMENT,
    user_id        BIGINT          NOT NULL,
    outlet_domain  VARCHAR(50)     NOT NULL,
    created_at     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP
                                             ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (setting_id),
    UNIQUE KEY uq_news_setting_user (user_id),
    FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS news_briefings (
    briefing_id    BIGINT          NOT NULL AUTO_INCREMENT,
    user_id        BIGINT          NOT NULL,
    outlet_domain  VARCHAR(50)     NOT NULL,
    briefing_date  DATE            NOT NULL,
    content        TEXT            NOT NULL,
    source_links   JSON            NOT NULL,
    created_at     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (briefing_id),
    UNIQUE KEY uq_news_briefing_user_date (user_id, briefing_date),
    INDEX idx_news_briefing_user (user_id, briefing_date),
    FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE
) ENGINE=InnoDB;

ALTER TABLE notifications
    MODIFY COLUMN type ENUM('SYSTEM','ORDER','AI','SIMULATION','NEWS') NOT NULL;

-- -----------------------------------------------------
-- 확인
-- -----------------------------------------------------
-- DESC news_briefing_settings;
-- DESC news_briefings;
-- SHOW COLUMNS FROM notifications LIKE 'type';
