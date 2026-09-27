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

## 2. (해소, 2026-09-21) StockSubscriptionManager의 mock 모드 처리 — MockMarketDataGenerator 도입으로 해결

- **등록**: 4주차 `feature/stock-price`
- **현상**: `MarketDataWebSocketClient`는 `market-data.mode=real`일 때만 스프링 빈으로 생성된다
  (`@ConditionalOnProperty(name = "market-data.mode", havingValue = "real")`). `mock` 모드
  (dev 기본값)에서는 이 빈이 존재하지 않아, 실시간 tick을 만들어낼 소스 자체가 없었다 —
  `local-market-data-generator`가 `market_data.json`을 주기적으로 갱신해도
  `StockBroadcastService`(STOMP 브로드캐스팅)를 트리거해줄 게 없어서, mock 모드에서는
  종목 상세 페이지를 새로고침해야만(REST 재조회) 새 값을 볼 수 있었다.
- **해결 (`feature/mock-broadcast`)**: `MockMarketDataGenerator`(domain/stock/service,
  `@ConditionalOnProperty(name = "market-data.mode", havingValue = "mock")`)를 추가해, 20초마다
  `market_data.json`을 폴링하고 `StockSubscriptionManager.getActiveSubscribedStockCodes()`로
  구독 중인 종목만 골라 `updatedAt` 변경을 감지, 바뀐 종목만 `MarketDataListener`
  (`StockBroadcastService`)에 tick/호가를 전달하도록 했다. `StockSubscriptionManager`의
  `subscribe()`/`unsubscribe()`는 여전히 `Optional<MarketDataWebSocketClient>` 패턴을 그대로
  쓴다(mock 모드에서 실제 외부 시세 데이터 구독 호출 자체가 필요 없는 건 변함없음) — 이번에 바뀐 건 "구독
  카운터를 유지하는 로직"이 아니라 "구독 중인 종목 목록을 외부에서 읽을 수 있는 조회 메서드
  (`getActiveSubscribedStockCodes()`)가 추가됐다"는 점뿐이라, 당초 우려했던 것과 달리
  `MarketDataWebSocketClient`/`MockMarketDataGenerator`를 공통 인터페이스로 묶는 재설계는 필요 없었다.
  상세 설계는 `NAMING.md`의 `MockMarketDataGenerator`/`LocalMarketDataReader.getAllCurrentPrices()`
  항목 참고.

---

## 3. WatchlistService 트랜잭션 안에서 외부 시세 데이터 소켓 I/O 호출

- **등록**: 4주차 `feature/stock-price`
- **현상**: `WatchlistService.addWatchlist()`/`removeWatchlist()`는 `@Transactional`
  범위 안에서 `StockSubscriptionManager.increase/decreaseWatchlistSubscription()`을
  호출하는데, `market-data.mode=real`이면 이 호출이 `MarketDataWebSocketClient.subscribe()`/
  `unsubscribe()`(외부 소켓 I/O)까지 이어진다. DB 트랜잭션이 열려 있는 동안 외부 I/O를
  기다리게 되어, 외부 시세 데이터 쪽이 느려지거나 예외를 던지면 관심종목 저장/삭제 자체가 지연되거나
  롤백된다.
- **영향**: `mock` 모드(dev 기본값)에서는 무해하다(`Optional.empty()`라 실제 I/O가
  없음). `real` 모드에서만 발현되며, 아직 실측으로 재현하지는 않았다.
- **이번 브랜치 처리**: 수정하지 않는다(범위 밖). 트랜잭션과 외부 I/O 호출을 분리하려면
  `increase/decreaseWatchlistSubscription()` 호출을 트랜잭션 커밋 이후로 미루는 방식
  (예: `TransactionSynchronization.afterCommit()` 또는 이벤트 발행 후 별도 리스너에서 처리)
  검토가 필요하다.
- **후속 조치**: `real` 모드로 실제 운영 부하를 겪어보고 지연·롤백이 실제로 문제가 되면
  별도 브랜치에서 트랜잭션 분리를 진행한다.
