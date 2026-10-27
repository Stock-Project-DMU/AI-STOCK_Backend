# db — 스키마와 마이그레이션

2026-10-13(`fix/overall-improvements`)에 저장소 맨 위에 흩어져 있던 SQL을 이 폴더로 옮겼다. **SQL 내용은 바꾸지 않았고**
위치와 파일 이름(실행 순서 번호)만 바꿨다. `schema.sql`만 빠져 있던 컬럼(users 정지 사유·기한)을 채워 v20으로 올렸고, 같은 날 엔티티와 다른 컬럼 타입 5개를
맞춰 v21로 올렸다(`016_column_types.sql`).

## 언제 무엇을 실행하나

| 상황 | 실행할 것 |
|---|---|
| 새 DB를 처음 만들 때(로컬·새 서버) | `schema.sql` 하나만 실행 — 최신 전체 스키마 |
| 이미 운영 중인 DB를 최신으로 올릴 때 | **먼저 DB를 백업**한 뒤, `migrations/`에서 아직 적용하지 않은 파일을 **번호 순서대로** 실행 |

- 운영 DB는 `spring.jpa.hibernate.ddl-auto=validate`라, 엔티티가 바뀐 버전을 배포하기 전에 해당 마이그레이션이 먼저 적용돼 있어야
  서버가 뜬다.
- 몇몇 파일은 이미 적용된 부분을 건너뛰도록 작성돼 있지만(존재 여부 확인 후 실행), 전부 그런 것은 아니다. 특히 데이터를 바꾸는
  `006_fund_tendency.sql`(자금성향 4단계 → 3단계)과 `011_account_single.sql`(유저당 계좌 1개로 정리)은 **두 번 실행하지 않는다.**

## 마이그레이션 목록 (실행 순서)

| 번호 | 파일 | 이전 이름 | 추가 시점 | 내용 |
|---|---|---|---|---|
| 001 | `001_ai_planning.sql` | `ai_planning_migration.sql` | 2026-08-13 | AI 재무설계 상담 테이블 보정 |
| 002 | `002_simulation_investment_amount.sql` | `simulation_investment_amount_migration.sql` | 2026-08-19 | simulations.investment_amount |
| 003 | `003_news_briefing.sql` | `news_briefing_migration.sql` | 2026-08-25 | 뉴스 브리핑 설정·브리핑 테이블 |
| 004 | `004_frontend_api.sql` | `frontend_api_migration.sql` | 2026-09-12 | 프론트 연동 신규 테이블 |
| 005 | `005_suspension.sql` | `suspension_migration.sql` | 2026-09-12 | users 정지 사유·정지 기한 |
| 006 | `006_fund_tendency.sql` | `fund_tendency_migration.sql` | 2026-09-29 | 자금성향 4단계 → 3단계 (데이터 변환) |
| 007 | `007_news_briefing_attempt.sql` | `news_briefing_attempt_migration.sql` | 2026-09-29 | 브리핑 마지막 시도 시각 |
| 008 | `008_notification_order_link.sql` | `notification_order_link_migration.sql` | 2026-09-29 | 주문 알림 ↔ 주문 연결 |
| 009 | `009_news_chat_session.sql` | `news_chat_session_migration.sql` | 2026-09-30 | 뉴스 채팅 세션·메시지 |
| 010 | `010_ai_planning_message_content.sql` | `ai_planning_message_content_migration.sql` | 2026-10-01 | 상담 메시지 본문 TEXT |
| 011 | `011_account_single.sql` | `account_single_migration.sql` | 2026-10-03 | 유저당 계좌 1개 정리 (데이터 변환) |
| 012 | `012_simulation_v2.sql` | `simulation_v2_migration.sql` | 2026-10-03 | 시뮬레이션 v2 구조 |
| 013 | `013_mypage_account.sql` | `mypage_account_migration.sql` | 2026-10-03 | 마이페이지 계좌 정보 보완 |
| 014 | `014_admin_improvements.sql` | `admin_improvements_migration.sql` | 2026-10-06 | 관리자 페이지 개선(1~8) |
| 015 | `015_dividend.sql` | `dividend_migration.sql` | 2026-10-06 | 배당 일정·권리, 거래 유형 DIVIDEND |
| 016 | `016_column_types.sql` | (신규) | 2026-10-13 | 엔티티와 컬럼 타입 맞춤(정수 3개 INT, 문의 본문·답변 TEXT) — 운영 validate 기동 조건 |

> 문서·주석에 남아 있는 이전 이름(`xxx_migration.sql`)은 위 표로 새 파일을 찾으면 된다. `schema.sql` 상단 버전 이력(v1~v19)도
> 당시 파일 이름 그대로 남겨 두었다.

## 새 마이그레이션을 추가할 때

1. 다음 번호로 `migrations/017_설명.sql`을 만든다(이미 적용된 부분은 건너뛰게 작성하는 것을 권장).
2. 같은 변경을 `schema.sql`에도 반영하고 상단 버전 이력을 올린다.
3. 이 README 표에 한 줄 추가한다.
