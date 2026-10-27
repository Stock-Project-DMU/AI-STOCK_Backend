# KNOWN_ISSUES.md — 알려진 미해결 이슈

이 문서는 특정 브랜치 범위에서는 의도적으로 해결하지 않고 넘어가기로 한
구조적 한계·미해결 이슈를 기록한다. NAMING.md/CLAUDE.md와 마찬가지로
실제 코드가 바뀌어 이슈가 해소되면 이 문서에서도 함께 제거한다(아래 "해소된 이슈"에 한 줄로 남긴다).

현재 미해결 이슈 없음.

---

## 해소된 이슈 (기록)

- **투자성향 채점의 4번 문항(투자경험) 방향** (`refactor/enhancement-plan-b`, 2026-09-28 등록) — 2026-10-13 프론트 확인 결과
  4번은 체크박스 중복 선택(1 선물·옵션 → 5 주식형 펀드, 위험한 순서)이고, 백엔드는 2026-09-29부터 가장 위험한 경험
  기준(`6 - 답`)으로 이미 맞게 채점하고 있었다. 같은 날 6번 "없음"(투자 경험 없음, 혼자만 선택, 0점)을 추가해
  `fix/overall-improvements`에서 해소(`SurveyTendencyEvaluator`, `UserService.saveSurvey()`).
- **신규/미거래 종목의 종목명 표시 문제** (4주차 `feature/stock-price` 등록) — 서비스 종목을 등록 종목 카탈로그
  (`stocks.json`, `RegisteredStockReader`)로 한정하고 local-market-data-generator 시세에 종목명이 함께 실리면서 해소.
- **StockSubscriptionManager의 mock 모드 처리** (4주차 등록) — 2026-09-21 `MockMarketDataGenerator` 도입으로 해소.
- **WatchlistService 트랜잭션 안의 외부 시세 소켓 I/O** (4주차 등록) — `fix/local-market-data-stable`(PR #68)에서
  외부 시세 WebSocket(real 모드)을 제거해 트랜잭션 안의 외부 I/O 자체가 없어지면서 해당 없음.
