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
  테이블에 없다. LS증권 WebSocket tick도 `LsTickData.stockName`이 항상 null로
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

## 2. StockSubscriptionManager의 mock 모드 처리 — MockLsDataGenerator 도입 시 재설계 필요

- **등록**: 4주차 `feature/stock-price`
- **현상**: `LsWebSocketClient`는 `ls.mode=real`일 때만 스프링 빈으로 생성된다
  (`@ConditionalOnProperty(name = "ls.mode", havingValue = "real")`). `mock` 모드
  (dev 기본값)에서는 이 빈이 존재하지 않는다.
- **이번 브랜치 처리**: `StockSubscriptionManager`가 `Optional<LsWebSocketClient>`로
  생성자 주입받아, mock 모드(`Optional.empty()`)에서는 구독자 수 카운터만 갱신하고
  실제 `subscribe()`/`unsubscribe()` 호출은 debug 로그만 남기고 건너뛴다. 임시방편이다.
- **후속 조치**: 나중에 `MockLsDataGenerator`(mock 모드용 가짜 tick 생성기)를 만들 때
  `StockSubscriptionManager`의 구독 호출 대상을 다시 설계해야 한다 — 예를 들어
  `LsWebSocketClient`와 `MockLsDataGenerator`가 공통 인터페이스를 구현하도록 추상화하고
  `StockSubscriptionManager`는 그 인터페이스 하나만 바라보게 바꾸는 방향을 검토한다
  (지금은 `MockLsDataGenerator`가 없어 추상화할 대상이 없으므로 `Optional`로 임시 처리).

---

## 3. WatchlistService 트랜잭션 안에서 LS 소켓 I/O 호출

- **등록**: 4주차 `feature/stock-price`
- **현상**: `WatchlistService.addWatchlist()`/`removeWatchlist()`는 `@Transactional`
  범위 안에서 `StockSubscriptionManager.increase/decreaseWatchlistSubscription()`을
  호출하는데, `ls.mode=real`이면 이 호출이 `LsWebSocketClient.subscribe()`/
  `unsubscribe()`(외부 소켓 I/O)까지 이어진다. DB 트랜잭션이 열려 있는 동안 외부 I/O를
  기다리게 되어, LS 쪽이 느려지거나 예외를 던지면 관심종목 저장/삭제 자체가 지연되거나
  롤백된다.
- **영향**: `mock` 모드(dev 기본값)에서는 무해하다(`Optional.empty()`라 실제 I/O가
  없음). `real` 모드에서만 발현되며, 아직 실측으로 재현하지는 않았다.
- **이번 브랜치 처리**: 수정하지 않는다(범위 밖). 트랜잭션과 외부 I/O 호출을 분리하려면
  `increase/decreaseWatchlistSubscription()` 호출을 트랜잭션 커밋 이후로 미루는 방식
  (예: `TransactionSynchronization.afterCommit()` 또는 이벤트 발행 후 별도 리스너에서 처리)
  검토가 필요하다.
- **후속 조치**: `real` 모드로 실제 운영 부하를 겪어보고 지연·롤백이 실제로 문제가 되면
  별도 브랜치에서 트랜잭션 분리를 진행한다.
