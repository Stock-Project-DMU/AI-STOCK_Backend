-- =====================================================
-- feature/simulation 1차 PR — simulations.investment_amount 컬럼 추가 보정 스크립트
-- 작성일: 2026-08-19
-- =====================================================
--
-- 배경: Simulation 엔티티에 investment_amount(BIGINT NOT NULL) 컬럼을 추가했다.
-- "목표 금액(target_amount) 도달"을 계산하려면 시작 원금이 필요한데, 기존
-- SimulationRequest(stockCode, targetAmount, targetMonths)에는 이 값이 없어
-- 도달 시점 계산 자체가 성립하지 않았다(사용자의 실제 보유 종목/수량과는 무관하게,
-- "이 금액을 투자한다면"이라는 가정으로 계산하는 필드다). schema.sql v10 → v11 참고.
--
-- 문제: application-prod.yml은 spring.jpa.hibernate.ddl-auto=validate라, Hibernate가
-- 스키마를 자동으로 만들거나 고쳐주지 않는다. 즉 이 컬럼이 반영되지 않은 상태로
-- 배포하면 엔티티와 실제 테이블이 불일치해 애플리케이션 기동 자체가 검증 실패로
-- 죽는다. 그래서 배포 전에 이 스크립트로 운영 DB에 컬럼을 먼저 반영해야 한다.
--
-- 적용 대상: simulations 테이블이 이미 생성돼 있는 모든 환경(로컬/개발/운영 공통).
-- application-dev.yml은 ddl-auto=update라 볼륨을 새로 만들어 처음 띄우는 로컬 DB라면
-- Hibernate가 investment_amount 컬럼까지 포함해서 새로 만들어주므로 이 스크립트를
-- 실행할 필요가 없다(docker compose down -v && docker compose up -d로 재생성).
-- 기존에 남겨둘 데이터가 있는 로컬/개발 DB이거나, ddl-auto=validate인 운영 DB라면
-- 아래 스크립트를 실행해야 한다.
--
-- DEFAULT 값 처리 근거: simulations 테이블에 실제로 행이 쌓이는 유일한 경로는
-- POST /api/simulations(runSimulation)인데, 이 엔드포인트는 feature/simulation
-- 1차 PR 시점까지 dev/prod 어디에도 존재한 적이 없다(SimulationController가
-- 그동안 빈 스텁이었다). 즉 이 테이블에 실제 데이터가 있을 가능성은 사실상 없다고
-- 봐도 되지만, "확실하다"고 스크립트가 스스로 단정하기보다는 안전장치로
-- DEFAULT 0을 둔다 — 혹시 남아있는 행이 있더라도 컬럼 추가 자체가 실패하지 않도록
-- 하기 위함이며, 실제 도달 계산 대상이 되는 값이 아니므로 0이어도 의미상 문제가 없다.

USE aistock;

ALTER TABLE simulations
    ADD COLUMN investment_amount BIGINT NOT NULL DEFAULT 0 AFTER target_amount;

-- -----------------------------------------------------
-- 확인
-- -----------------------------------------------------
-- DESC simulations;
-- SELECT simulation_id, target_amount, investment_amount FROM simulations LIMIT 5;
