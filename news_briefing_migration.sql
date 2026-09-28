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

-- =====================================================
-- v14 → v15 보정 — news_briefing_settings.briefing_hour(TINYINT, 0~23시)
-- → briefing_time(TIME, 시:분:초)로 변경
-- 작성일: 2026-09-28
-- =====================================================
--
-- 배경: 브리핑 생성 시각을 시 단위(0~23)가 아니라 분·초 단위까지 고를 수 있게
-- 바꾸면서 Entity(NewsBriefingSetting)가 더 이상 briefing_hour가 아니라
-- briefing_time 컬럼을 사용한다. schema.sql v14 → v15 참고.
--
-- 문제: application-prod.yml은 ddl-auto=validate라 Hibernate가 컬럼을 자동으로
-- 추가·삭제해주지 않는다. 이 스크립트로 먼저 반영하지 않으면 prod는 briefing_time이
-- 없어 기동 자체가 검증 실패로 죽고, application-dev.yml(ddl-auto=update)은 컬럼을
-- 자동으로 추가는 해주지만 DEFAULT 없이 NOT NULL로 추가되는 환경이면 기존 행이
-- '00:00:00'으로 채워져 사용자가 설정해둔 적 없는 시각처럼 보일 수 있다.
--
-- 적용 대상: news_briefing_settings 테이블이 이미 생성돼 있는 모든 환경(로컬/개발/운영
-- 공통). 완전히 새 환경(테이블을 이제 막 만드는 경우)은 위 CREATE TABLE 문 자체엔
-- briefing_time/briefing_hour 컬럼이 없지만, application-dev.yml처럼 ddl-auto=update로
-- Hibernate가 Entity(NewsBriefingSetting, 이미 briefing_time 필드로 정의됨) 기준으로
-- 테이블을 직접 만드는 환경이라면 Hibernate가 알아서 briefing_time을 만들어주므로 이
-- 스크립트가 필요 없다 — 정보_스키마로 존재 여부를 직접 확인 후 필요한 구문만 실행하므로
-- 정상적으로 끝까지 완료된 뒤에는 몇 번을 다시 실행해도 안전하다(idempotent). 다만 2번
-- 단계(UPDATE)는 "이번 실행에서 방금 briefing_time을 새로 추가했을 때만" 돌게 해뒀다 —
-- 그렇지 않고 3번 단계(DROP COLUMN)만 실패해 briefing_hour가 남아있는 상태로 재실행되면,
-- 그 사이 사용자가 API로 바꿔둔 briefing_time 값을 옛 briefing_hour 값으로 덮어쓸 위험이
-- 있기 때문이다. 단, 이 스크립트는 한 번에 한 세션에서만 실행된다는 전제다 — 두 배포
-- 파이프라인이 동시에 돌리는 경우(둘 다 같은 정보_스키마 조회 결과를 읽고 동시에 ALTER를
-- 시도)까지는 막지 않는다. 이 경우 "안전하다"는 건 스크립트를 표준 mysql 클라이언트로
-- 실행해 첫 에러("Duplicate column name")가 나면 그 세션이 즉시 중단되는 실행 방식일
-- 때의 얘기다 — 만약 `mysql --force`처럼 에러가 나도 다음 statement로 계속 진행하는
-- 방식으로 돌리면, 진 세션이 ALTER 이전에 읽어둔 낡은 @has_briefing_hour/@has_briefing_time
-- 스냅샷으로 UPDATE/DROP까지 이어서 실행해버려 이 문단이 막으려던 덮어쓰기 위험이 다시
-- 생길 수 있다 — 배포 스크립트는 항상 기본(에러 시 중단) 방식으로 실행할 것.

USE aistock;

SET @has_briefing_hour = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'news_briefing_settings'
      AND COLUMN_NAME = 'briefing_hour'
);
-- 이 값은 위 ALTER(1번 단계)가 실행되기 전에 읽은 스냅샷이라, "이번 실행에서 방금
-- briefing_time을 새로 추가했는지"를 그대로 알려준다 — 2번 단계(UPDATE)를 재실행 시
-- 덮어쓰지 않도록 가드하는 데 그대로 재사용한다(별도 변수 불필요).
SET @has_briefing_time = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'news_briefing_settings'
      AND COLUMN_NAME = 'briefing_time'
);

-- 1) briefing_time 컬럼이 아직 없으면 추가 (기본값 07:00:00 — 기존 고정 새벽 7시와 동일)
SET @add_time_sql = IF(@has_briefing_time = 0,
    'ALTER TABLE news_briefing_settings ADD COLUMN briefing_time TIME NOT NULL DEFAULT ''07:00:00''',
    'SELECT 1');
PREPARE stmt FROM @add_time_sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2) briefing_hour 값이 있던 사용자는 그 값을 briefing_time(분·초는 0)으로 그대로 옮긴다.
-- "방금 1번에서 briefing_time을 새로 추가했을 때"(@has_briefing_time이 ALTER 전엔 0)만
-- 실행한다 — briefing_time이 이미 존재했다면(예: 이전 실행에서 3번 DROP만 실패해 재실행된
-- 경우) 그사이 사용자가 API로 바꿔둔 값이 있을 수 있으므로 다시 덮어쓰지 않는다.
SET @migrate_sql = IF(@has_briefing_hour > 0 AND @has_briefing_time = 0,
    'UPDATE news_briefing_settings SET briefing_time = MAKETIME(briefing_hour, 0, 0)',
    'SELECT 1');
PREPARE stmt FROM @migrate_sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 3) 옛 briefing_hour 컬럼은 더 이상 Entity가 쓰지 않으므로 제거
SET @drop_hour_sql = IF(@has_briefing_hour > 0,
    'ALTER TABLE news_briefing_settings DROP COLUMN briefing_hour',
    'SELECT 1');
PREPARE stmt FROM @drop_hour_sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- -----------------------------------------------------
-- 확인
-- -----------------------------------------------------
-- DESC news_briefing_settings;
-- DESC news_briefings;
-- SHOW COLUMNS FROM notifications LIKE 'type';
