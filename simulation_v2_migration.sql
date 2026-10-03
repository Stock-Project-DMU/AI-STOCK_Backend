-- =====================================================
-- feature/goal-simulation-v2 — simulations 테이블 구조 변경 스크립트
-- 작성일: 2026-10-01
-- =====================================================
--
-- 배경: 목표 도달 시뮬레이션을 "종목 1개 + 가정한 투자 원금" 구조에서 "내 보유종목 유지 vs
-- 투자성향 리밸런싱" 비교 구조로 바꿨다. 컬럼 구성(stock_code, investment_amount, best/base/worst
-- 등)이 전부 달라져 기존 행은 새 화면에서 읽을 수 없으므로, 기존 데이터는 삭제하고 테이블을
-- 새 구조로 다시 만든다(팀 결정 — 기존 데이터 삭제).
--
-- 적용 대상: simulations 테이블이 이미 있는 모든 환경(로컬/개발/운영 공통).
-- application-dev.yml의 ddl-auto=update는 컬럼을 추가만 하고 기존 NOT NULL 컬럼(stock_code 등)을
-- 지우지 않아, 이 스크립트 없이 띄우면 새 시뮬레이션 저장(INSERT)이 실패한다. 운영(ddl-auto=validate)은
-- 이 스크립트 없이 배포하면 기동 검증에서 실패한다. 반드시 배포 전에 실행한다.
-- 다른 테이블이 simulations를 참조하지 않으므로(FK 없음) DROP해도 영향받는 테이블은 없다.

DROP TABLE IF EXISTS simulations;

CREATE TABLE simulations (
    simulation_id              BIGINT       NOT NULL AUTO_INCREMENT,
    user_id                    BIGINT       NOT NULL,
    goal_text                  VARCHAR(200) NOT NULL,    -- 사용자가 입력한 목표 문장
    target_amount              BIGINT       NOT NULL,    -- 목표 금액 (원)
    period_months              INT,                      -- 목표 기한 (개월), 기한 없으면 NULL
    start_amount               BIGINT       NOT NULL,    -- 시작 금액 (보유종목 평가금액 + 예수금)
    monthly_contribution       BIGINT       NOT NULL,    -- 월 추가 납입액 (원)
    current_reach_date         DATE,                     -- 현재 보유 유지 시 목표 도달 예상일 (30년 내 미도달이면 NULL)
    rebalanced_reach_date      DATE,                     -- 리밸런싱 시 목표 도달 예상일 (30년 내 미도달이면 NULL)
    projection_data            JSON         NOT NULL,    -- 두 포트폴리오 구성·곡선·도달 시점
    rebalance_reason           TEXT         NOT NULL,    -- Gemini 리밸런싱 이유
    time_reduction_explanation TEXT         NOT NULL,    -- Gemini 목표 도달 시간 단축 설명
    dart_data                  JSON,                     -- Open DART 재무 원본 (종목코드별)
    news_data                  JSON,                     -- 네이버 뉴스 검색 원본 (종목코드별)
    created_at                 DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (simulation_id),
    INDEX idx_user_sim (user_id),
    FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE
) ENGINE=InnoDB;
