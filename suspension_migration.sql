-- 운영 DB 배포 시 1회 적용. 개발 환경은 Hibernate ddl-auto=update로 반영.
ALTER TABLE users ADD COLUMN suspension_reason VARCHAR(500) NULL,
                  ADD COLUMN suspended_until DATETIME(6) NULL;
