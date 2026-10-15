-- =====================================================
-- 엔티티와 컬럼 타입 맞춤 마이그레이션 (schema.sql v21, fix/overall-improvements, 2026-10-13)
-- =====================================================
-- 운영 DB(ddl-auto=validate)는 이 컬럼들의 타입이 엔티티와 다르면 서버가 뜨지 않는다. 개발 DB(ddl-auto=update)는 타입 변경을
-- 반영하지 않으므로 개발 DB에도 직접 적용한다(특히 2번 — @Lob 때문에 TINYTEXT(255바이트)로 만들어져 긴 문의가 저장되지 않았다).
--
--   1. accounts.charge_count, investment_profile.investment_tendency / fund_tendency: TINYINT → INT (엔티티 int와 동일)
--   2. inquiries.content / answer: TEXT (엔티티 columnDefinition="TEXT"와 동일)
--
-- 값은 그대로 유지된다(TINYINT → INT, TINYTEXT → TEXT 모두 범위가 넓어지는 변경). 이미 같은 타입이어도 결과가 같아 재실행해도 안전하다.
-- 적용 전 확인(운영):
--   SELECT table_name, column_name, column_type FROM information_schema.columns
--    WHERE table_schema = 'aistock'
--      AND (table_name, column_name) IN (('accounts','charge_count'), ('investment_profile','investment_tendency'),
--                                        ('investment_profile','fund_tendency'), ('inquiries','content'), ('inquiries','answer'));
USE aistock;

-- 1. 정수 컬럼
ALTER TABLE accounts
    MODIFY charge_count INT NOT NULL DEFAULT 0;

ALTER TABLE investment_profile
    MODIFY investment_tendency INT NOT NULL COMMENT '1:안정형 2:안정추구형 3:위험중립형 4:적극투자형 5:공격투자형',
    MODIFY fund_tendency       INT NOT NULL COMMENT '1:수익추구형 2:자유소비형 3:목표달성형';

-- 2. 문의 본문·답변
ALTER TABLE inquiries
    MODIFY content TEXT NOT NULL,
    MODIFY answer  TEXT NULL;
