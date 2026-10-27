-- =====================================================
-- AI 재무설계(feature/ai-planning) 기존 DB 보정 스크립트
-- 작성일: 2026-07-31
-- =====================================================
--
-- 배경: AiPlanningMessage 엔티티를 두 가지 이유로 고쳤다.
--   1) content 컬럼을 @Lob → @Column(columnDefinition = "TEXT")로 변경 (TINYTEXT로 생성되어
--      "Data too long" 에러가 나던 문제 수정)
--   2) session_id FK에 @OnDelete(CASCADE) 추가 (세션 벌크 삭제 시 메시지가 남아 FK 위반으로
--      실패하던 문제 수정)
--
-- 문제: 이 서버는 spring.jpa.hibernate.ddl-auto=update를 쓰는데, Hibernate의 SchemaUpdate는
-- 이미 존재하는 컬럼의 타입이나 이미 존재하는 FK 제약을 알아서 고쳐주지 않는다(새로 만드는
-- 테이블에는 정상 반영됨). 그래서 이 두 변경 사항이 적용되기 "이전"에 이미 서버를 한 번이라도
-- 띄워서 ai_planning_messages 테이블이 생성된 적이 있는 DB에는 코드만 고쳐서는 반영되지 않는다.
--
-- 적용 대상: 로컬/개발 DB 중 ai_planning_messages 테이블이 이미 존재하는 경우만 해당한다.
-- docker-compose로 볼륨을 새로 만들어 처음 띄우는 DB라면 Hibernate가 새 스키마 그대로 만들어
-- 주므로 이 스크립트를 실행할 필요가 없다(그냥 서버를 재기동해서 새로 생성되게 하면 된다).
--
-- 실행 전 확인: 아래 두 방법 중 편한 쪽으로 먼저 현재 상태를 확인한다.
--   1) docker compose down -v && docker compose up -d 로 볼륨째 재생성 (로컬 개발 DB에 남겨둘
--      데이터가 없다면 이 방법이 가장 간단하고 확실하다 — 이 스크립트를 실행할 필요가 없어진다)
--   2) 남겨둘 데이터가 있다면, 아래 SQL을 그대로 실행해 기존 테이블만 보정한다.

USE aistock;

-- -----------------------------------------------------
-- 1) content 컬럼 타입 보정: TINYTEXT(또는 기존 타입) → TEXT
-- -----------------------------------------------------
ALTER TABLE ai_planning_messages
    MODIFY COLUMN content TEXT NOT NULL;

-- -----------------------------------------------------
-- 2) session_id FK에 ON DELETE CASCADE 추가
-- -----------------------------------------------------
-- FK 제약 이름은 MySQL이 자동으로 붙인 이름이라 환경마다 다를 수 있다. 아래 조회로 먼저
-- 실제 제약 이름을 확인한 뒤, 그 이름으로 DROP/ADD 한다.
--
--   SELECT CONSTRAINT_NAME
--   FROM information_schema.KEY_COLUMN_USAGE
--   WHERE TABLE_SCHEMA = 'aistock'
--     AND TABLE_NAME = 'ai_planning_messages'
--     AND COLUMN_NAME = 'session_id'
--     AND REFERENCED_TABLE_NAME = 'ai_planning_sessions';
--
-- 위 조회 결과가 보통 `ai_planning_messages_ibfk_1` 형태다. 그 값을 아래 <FK_CONSTRAINT_NAME>
-- 자리에 그대로 넣어서 실행한다 (예시 이름을 그대로 실행하면 없는 제약이라 에러가 날 수 있다).

-- ALTER TABLE ai_planning_messages
--     DROP FOREIGN KEY <FK_CONSTRAINT_NAME>;
--
-- ALTER TABLE ai_planning_messages
--     ADD CONSTRAINT <FK_CONSTRAINT_NAME>
--     FOREIGN KEY (session_id) REFERENCES ai_planning_sessions(session_id)
--     ON DELETE CASCADE;

-- -----------------------------------------------------
-- 3) 확인
-- -----------------------------------------------------
-- SHOW CREATE TABLE ai_planning_messages;
-- 위 결과에서 `content` TEXT NOT NULL 이고, FK 정의 끝에 ON DELETE CASCADE가 붙어있는지 확인한다.
