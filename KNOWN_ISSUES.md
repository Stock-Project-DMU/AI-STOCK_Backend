# KNOWN_ISSUES.md — 알려진 미해결 이슈

이 문서는 특정 브랜치 범위에서는 의도적으로 해결하지 않고 넘어가기로 한
구조적 한계·미해결 이슈를 기록한다. NAMING.md/CLAUDE.md와 마찬가지로
실제 코드가 바뀌어 이슈가 해소되면 이 문서에서도 함께 제거한다.

---

## 1. 신규/미거래 종목의 종목명(stockName) 표시 문제

- **등록**: 4주차 `feature/stock-price`
- **현상**: 우리 DB(13개 테이블) 중 `holdings`/`orders`/`watchlist`/`recent_viewed`
  4곳에만 `stock_name`이 저장되며, 그마저도 "누군가 그 종목을 이미 관심등록·매매·
  조회한 적 있을 때만" 채워진다. 별도의 "종목 마스터" 테이블이 schema.sql 13개
  테이블에 없다. 외부 시세 데이터 제공사 WebSocket tick도 `TickData.stockName`이 항상 null로
  온다(3주차 실측 확인).
- **영향**: 아무도 다뤄본 적 없는 신규 상장 종목이나 미거래 종목은 실시간 시세
  브로드캐스팅·조회 API 응답에서 종목명 대신 종목코드만 노출된다
  (`StockNameResolver.resolveStockName()`이 null을 반환하는 경우,
  `StockBroadcastService`가 `stockCode`로 대체).
- **이번 브랜치(feature/stock-price) 처리**: 근본 해결 없이 위 폴백만 적용하고
  범위 밖으로 남긴다.
- **후보 해결책 (팀 회의에서 결정 필요)**:
  1. 5주차에 만들 `DartApiClient`(infra/dart)에 종목명 조회용 API(기업개황 등)
     호출 메서드를 추가해, DB에 없으면 DART로 폴백 조회.
  2. `stock_master` 테이블을 새로 만들고 상장 종목 전체를 배치로 한 번
     채워넣는 방식. 스키마 변경이 필요해 별도 브랜치로 진행해야 한다.

---

## 2. (해소, 2026-09-21) StockSubscriptionManager 구독 카운트만으로 실시간 시세 전송 — MockMarketDataGenerator 도입으로 해결

- **등록**: 4주차 `feature/stock-price`
- **해결 (`feature/mock-broadcast`)**: `MockMarketDataGenerator`가 `market_data.json`을 폴링하고
  `StockSubscriptionManager.getActiveSubscribedStockCodes()`로 구독 중인 종목만 골라 바뀐 tick/호가를
  `MarketDataListener`(`StockBroadcastService`)에 전달한다. 상세 설계는 `NAMING.md`의
  `MockMarketDataGenerator`/`LocalMarketDataReader.getAllCurrentPrices()` 항목 참고.

---

## 3. (해소, 2026-10-06) WatchlistService 트랜잭션 안에서 외부 시세 데이터 소켓 I/O 호출

- **등록**: 4주차 `feature/stock-price`
- **해소**: fix/local-market-data-stable에서 외부 시세 데이터 실시간 구독 자체를 없앴다.
  `StockSubscriptionManager`는 메모리 카운터만 갱신하므로 관심종목 트랜잭션 안에 외부 I/O가 없다.

---

## 4. 투자성향 채점(4번 문항 가중치)이 프론트 선택지 순서와 맞는지 미확인

- **등록**: `refactor/enhancement-plan-b`, 2026-09-28 (wuhyuk 리뷰 반려 사유 3번 중 일부)
- **현상**: `SurveyTendencyEvaluator.evaluateInvestmentTendency()`는 4번 문항(투자경험)
  답변 번호가 클수록 투자성향 점수가 높아지도록(`+2 * answers.get(3)`) 계산한다.
  이 계산은 "4번 문항 선택지 번호가 클수록 투자 경험이 많다"는 걸 전제로 하는데,
  실제 프론트가 이 문항의 선택지를 그 순서로 배치했는지 백엔드에서 확인할 방법이
  없다(자금성향 1번 문항처럼 `FRONTEND_API_IMPLEMENTATION.md`에 매핑이 문서화돼
  있지 않음). 리뷰어는 이 가중치가 "역방향 적용"됐다고 지적했는데, 프론트 선택지
  순서를 모르는 채로 방향을 뒤집으면 추측으로 또 다른 오답을 만들 위험이 있어
  이번 브랜치에서는 손대지 않았다(같은 반려 사유의 자금성향 부분은
  `SurveyLevelEvaluator`/`SurveyTendencyEvaluator`에서 문서 근거로 이미 수정함).
- **영향**: 투자성향 채점 결과가 프론트가 보여주는 실제 등급과 다르게 나올 수 있음.
- **프론트 확인 필요 사항**: 설문 4번 문항(투자경험)의 선택지가 실제로 몇 번이
  "경험 많음"이고 몇 번이 "경험 없음"인지 (오름차순/내림차순 여부).
- **후속 조치**: 프론트 담당자 확인 후, 필요하면 `evaluateInvestmentTendency()`의
  `answers.get(3)` 항을 프론트 순서에 맞게 반전(`(5 - answers.get(3))` 등)하거나
  가중치 부호를 조정한다.
