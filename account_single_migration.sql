-- =====================================================
-- feature/goal-simulation-v2 — 유저당 계좌 1개 제한 스크립트
-- 작성일: 2026-10-01
-- =====================================================
--
-- 배경: 목표 도달 시뮬레이션이 "내 보유종목 + 예수금"을 계좌 하나로 특정해야 해서, 유저당 최대
-- 3개였던 계좌를 1개로 줄였다(AccountService.MAX_ACCOUNT_COUNT = 1). 이미 계좌가 2개 이상인
-- 유저는 가장 먼저 만든 계좌(account_id가 가장 작은 계좌)만 남기고 나머지를 삭제한다(팀 결정).
--
-- ⚠️ 삭제되는 계좌의 보유종목(holdings)·주문(orders)·충전요청(charge_requests)·잔고 원장
-- (account_transactions)도 FK ON DELETE CASCADE로 함께 삭제된다. 되돌릴 수 없으므로 운영 DB에는
-- 반드시 백업 후 실행한다. notifications.related_order_id / account_transactions.related_order_id는
-- FK가 아닌 참조용 값이라 삭제된 주문을 가리키는 알림이 남을 수 있다(알림 자체는 그대로 보인다).
--
-- 적용 대상: 로컬/개발/운영 공통. 1번 SELECT로 삭제 대상을 먼저 확인한 뒤 2·3번을 실행한다.

-- 1. 삭제 대상 확인(유저별로 남길 계좌를 제외한 나머지)
SELECT a.user_id, a.account_id, a.account_name, a.balance
FROM accounts a
JOIN (SELECT user_id, MIN(account_id) AS keep_account_id FROM accounts GROUP BY user_id) k
  ON a.user_id = k.user_id
WHERE a.account_id <> k.keep_account_id;

-- 2. 유저별 첫 계좌만 남기고 삭제
DELETE a
FROM accounts a
JOIN (SELECT user_id, MIN(account_id) AS keep_account_id FROM accounts GROUP BY user_id) k
  ON a.user_id = k.user_id
WHERE a.account_id <> k.keep_account_id;

-- 3. 유저당 계좌 1개를 DB 제약으로 보장(Account 엔티티의 @UniqueConstraint와 같은 이름)
ALTER TABLE accounts ADD CONSTRAINT uq_account_user UNIQUE (user_id);
