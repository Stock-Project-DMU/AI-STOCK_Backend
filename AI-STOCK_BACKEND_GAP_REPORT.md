# AI-STOCK 프론트 UI 기준 백엔드·환경설정 점검

작성일: 2026-09-12 (한국 시간)  
최종 갱신: 2026-09-12 — 관심 종목·주문·최근 본 종목·AI 상담·뉴스 검색 채팅·언론사 표시 수정 반영
대상: 현재 로컬 `AI-STOCK_Frontend`, `AI-STOCK_Backend/aistock` 작업본 및 실행 중인 개발 서버

## 1. 핵심 결론

현재 주요 프론트 호출에 대응하는 백엔드 API는 대부분 구현되어 있다. **이메일 발송 API가 없는 상태가 아니라 SMTP 설정이 없는 상태**이며, 소셜 로그인도 동일하게 API 구현과 외부 서비스 설정을 구분해야 한다.

확인된 작업은 아래처럼 분류한다.

| 분류 | 의미 |
|---|---|
| 설정 누락 | API·서비스 코드는 있으나 인증정보나 발송 설정이 없음 |
| 외부 연동 장애 | 호출 코드는 있으나 외부 제공자 또는 데이터 공급 단계에서 실패 |
| 구현 보완 | API는 있지만 UI 요구사항을 완전히 충족하지 못하는 처리 경로가 있음 |
| 검증 필요 | 코드와 설정 존재만 확인했으며 실제 성공 여부는 미확인 |
| 현재 구현됨 | 미구현 목록에 넣으면 안 되는 기능 |

이 문서는 현재 남은 작업 목록이며, 완료된 수정은 7절로 분리했다. 이번 갱신은 코드 대조와 환경변수의 존재 여부 확인이며 외부 서비스를 새로 호출한 검증이 아니다. 메일을 실제 발송하거나, 다른 회원의 비밀번호·정지 상태를 변경하거나, 유료 AI 요청을 발생시키는 통합 검증은 수행하지 않았다. 키의 존재는 유효성·권한·잔여 한도를 의미하지 않는다. 비밀값은 기재하지 않았다.

## 2. 우선순위별 작업 목록

| 우선순위 | UI / 기능 | 판정 | 필요한 작업 |
|---|---|---|---|
| P1 | 회원가입 이메일 인증 | SMTP 설정 누락 | 발신 계정·인증정보 설정 후 발송/인증 통합 검증 |
| P1 | 아이디 찾기·비밀번호 재설정 | 동일한 SMTP 설정에 의존 | 공통 인증메일 설정 후 복구 흐름 검증 |
| P1 | Google·네이버·카카오 로그인 | OAuth 설정 누락, 실제 503 확인 | 공급자 앱 등록 및 콜백/키 설정 |
| P1 | 종목·지수·호가·차트 | 오전 LS 점검 종료, 일부 조회 정상 확인 + 구현 보완 | 미검증 TR·호가 공급 경로 확인, 빈 목록으로 오류를 숨기는 처리 개선, ETF 신규 필드 반영 |
| P1 | 비회원 호가 | 데이터 공급 경로 보완 | 로그인 없는 조회에서도 캐시 미스 대응 |
| P1 | 설문으로만 성향·레벨 변경 | 서버 변경 경로 제한 미완료 | 직접 갱신 API 정리, 성향도 서버에서 산출 |
| P2 | 소셜 계정 내 정보 수정·탈퇴 | 재인증 흐름 보완 | 비밀번호 없는 계정용 재인증/변경/탈퇴 정책 구현 |
| P2 | 뉴스 검색 채팅·정기 브리핑 | 구현 완료, 실제 외부 연동 검증 필요 | 채팅 검색·후속 질문·출처 응답 및 오전 7시 생성·저장 확인 |
| P2 | AI 상담·재무자료 | 실제 연동 검증 필요 | Gemini·DART 성공/실패·권한·한도 확인 |
| P2 | 지정가 실시간 체결 | 등록·취소 구현 및 테스트 완료, 전체 운용 검증 필요 | 실제 LS 수신 → 가격 조건 판단 → 체결·잔고·알림 확인 |
| P2 | 홈 상승순·하락순 | 전체 시장 순위와 현재 목록 정렬의 차이 | 요구 범위 확정 후 전체 순위 API 연결/확장 |
| P3 | 환경파일 관리 | 미사용 변수 잔존 | 실제 참조 변수명과 예시 파일 정리 |

## 3. 설정 누락 상세

### 3.1 회원가입 이메일 인증

**API는 존재한다.**

- `POST /api/auth/email/send-code`: 인증번호 발송
- `POST /api/auth/email/verify-code`: 인증번호 확인
- `AuthService.sendEmailCode()` → Redis 저장 → `MailClient.sendAuthCode()` → `JavaMailSender.send()`
- 인증번호는 5분, 인증 완료 표시는 30분 유효하도록 구현되어 있다.

현재 백엔드 `.env`에는 `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM`이 없다. 점검 시점의 도구 프로세스 환경에도 `MAIL_*`가 없었다. 실행 JVM에 별도 인자를 주입했는지까지 완전히 증명한 것은 아니지만, 앞서 실행한 기본 개발 서버 설정에서는 발송 계정이 준비되지 않은 상태다.

`application-dev.yml`의 기본 SMTP 주소는 `smtp.gmail.com:587`, 사용자명·비밀번호 기본값은 빈 문자열이다. `MAIL_FROM` 미설정 시 발신자는 `aistock@example.com`이다. 이 기본값만으로 실제 발송이 가능하다고 볼 수 없다.

**조치**

1. 사용할 SMTP 계정과 인증 방식을 결정한다.
2. `.env`에 아래 값을 설정한다. 비밀번호는 프론트 환경파일에 넣지 않는다.
3. 백엔드 재시작 후 실제 수신 가능한 테스트 주소로 발송 → 코드 확인 → 가입을 검증한다.
4. 틀린 코드, 만료 코드, 재사용, 발송 실패를 확인한다.

```dotenv
# AI-STOCK_Backend/aistock/.env — 예시이며 실제 인증값 입력 필요
MAIL_HOST=smtp.gmail.com
MAIL_PORT=587
MAIL_USERNAME=<발신 계정>
MAIL_PASSWORD=<해당 SMTP 서비스에서 요구하는 인증정보>
MAIL_FROM=<허용된 발신 이메일>
```

근거: [프론트 인증 API](AI-STOCK_Frontend/src/lib/api/auth.ts), [AuthService](AI-STOCK_Backend/aistock/src/main/java/com/teamfp/aistock/domain/auth/service/AuthService.java), [MailClient](AI-STOCK_Backend/aistock/src/main/java/com/teamfp/aistock/infra/mail/MailClient.java), [개발 설정](AI-STOCK_Backend/aistock/src/main/resources/application-dev.yml), [Redis 인증코드](AI-STOCK_Backend/aistock/src/main/java/com/teamfp/aistock/global/redis/RedisAuthCodeService.java).

### 3.2 비밀번호 변경과 비밀번호 찾기의 차이

| 흐름 | 구현된 API | 이메일 의존성 |
|---|---|---|
| 로그인한 사용자의 현재 비밀번호 확인 | `POST /api/users/me/password/verify` | 없음 |
| 로그인한 사용자의 비밀번호 변경 | `PATCH /api/users/me/password` 또는 `PATCH /api/users/me/profile`의 `passwordChange` | 없음. 현재 비밀번호로 검증 |
| 비밀번호를 잊은 사용자의 재설정 | `POST /api/auth/password/reset` | 있음. 공통 이메일 인증번호 사용 |
| 아이디 찾기 | `POST /api/auth/find-id` | 있음. 공통 이메일 인증번호 사용 |

복구 UI는 `RecoveryVerification`에서 `/api/auth/email/send-code`를 호출한다. 백엔드는 입력한 코드와 계정 정보 검증 후 아이디를 반환하거나 비밀번호를 변경한다. **복구 전용 이메일 전송 API를 새로 만들어야 하는 것은 아니다.** 공통 SMTP 설정을 먼저 완료해야 한다.

완료 기준: 코드 발송 → 실제 수신 → 본인 정보·코드 확인 → 비밀번호 재설정 → 새 비밀번호 로그인, 기존 비밀번호 로그인 실패 확인. 로그인 중 변경은 현재 비밀번호의 정답/오답 처리를 별도로 검증한다.

근거: [복구 인증 UI](AI-STOCK_Frontend/src/features/account-recovery/components/RecoveryVerification.tsx), [사용자 API](AI-STOCK_Frontend/src/lib/api/user.ts), [AuthService](AI-STOCK_Backend/aistock/src/main/java/com/teamfp/aistock/domain/auth/service/AuthService.java), [UserService](AI-STOCK_Backend/aistock/src/main/java/com/teamfp/aistock/domain/user/service/UserService.java).

### 3.3 소셜 로그인 3종

로그인 버튼, 인증 시작, 공급자 토큰 교환, 콜백 처리 코드는 존재한다.

- `GET /api/auth/oauth/{provider}/authorize`
- `POST /api/auth/oauth/login`
- 프론트 콜백: `/oauth/callback/[provider]`

점검 시 Google·NAVER·KAKAO 인증 시작 API 모두 **HTTP 503 / “소셜 로그인 제공자 설정이 아직 완료되지 않았습니다.”**를 반환했다.

| 공급자 | 필요한 변수 |
|---|---|
| Google | `GOOGLE_OAUTH_CLIENT_ID`, `GOOGLE_OAUTH_CLIENT_SECRET`, `GOOGLE_OAUTH_REDIRECT_URI` |
| 네이버 | `NAVER_OAUTH_CLIENT_ID`, `NAVER_OAUTH_CLIENT_SECRET`, `NAVER_OAUTH_REDIRECT_URI` |
| 카카오 | `KAKAO_OAUTH_CLIENT_ID`, `KAKAO_OAUTH_REDIRECT_URI`; 설정한 경우 `KAKAO_OAUTH_CLIENT_SECRET` |

현재 `.env`의 `NAVER_CLIENT_ID`, `NAVER_CLIENT_SECRET`은 **뉴스 검색용**이다. 네이버 로그인용 변수와 이름·용도가 다르다. 카카오 비밀키는 현재 코드에서 필수가 아니지만 공급자 앱 설정에 따라 필요할 수 있다.

조치: 공급자 앱 등록 → 허용된 콜백 URL과 환경변수 값 일치 → 백엔드 재시작 → 동의/취소/콜백/state 만료/이메일 미제공 케이스 검증. 콜백 주소는 현재 프론트 경로를 기준으로 등록하고 공급자에 등록된 값과 정확히 맞춘다.

근거: [프론트 OAuth](AI-STOCK_Frontend/src/lib/api/oauth.ts), [공급자 연동](AI-STOCK_Backend/aistock/src/main/java/com/teamfp/aistock/infra/oauth/OAuthProviderClient.java).

## 4. 백엔드 구현 보완이 필요한 부분

### 4.1 시장 데이터 오류 처리·ETF 변경 반영

오전 조회 장애는 **2026-09-12 LS 점검**으로 확인되었다. 점검 이후 토큰 발급과 일부 시세 조회가 성공했으므로 키 누락 또는 전체 API 미구현으로 분류하지 않는다. 기존 호출별 측정 이력은 완료 항목에 요약했다.

이미 적용된 토큰 재사용·짧은 시세 캐시·LS 전용 타임아웃은 추가 구현 대상에서 제외한다. 남은 작업은 다음과 같다.

- `LsHighItemApiClient` 등이 외부 오류를 빈 목록으로 반환하고, 지수도 일부 조회 실패 시 부분 목록을 반환한다. 정상적인 자료 없음과 공급 장애를 응답·화면에서 구분해야 한다.
- LS `t1901`, `t1904` 요청에 신규 입력 필드 `exchgubun`이 아직 없다. 공식 명세를 반영하고 ETF 구성종목 데이터까지 확인해야 한다. 기존 요청도 정상 응답 코드를 받은 적이 있어, 필드 누락이 오전 전체 장애의 원인이라는 뜻은 아니다.
- `t8451~t8453`은 현재 백엔드에서 사용하지 않는다. 기간별주가는 `t1305`를 사용한다.
- 순위·지수·차트의 지속적인 조회 안정성과 오류 표시를 검증해야 한다. 이전 점검에서 정상 응답과 빈 결과가 번갈아 발생했다.

근거: [LS 상위종목](AI-STOCK_Backend/aistock/src/main/java/com/teamfp/aistock/infra/ls/LsHighItemApiClient.java), [ETF 조회](AI-STOCK_Backend/aistock/src/main/java/com/teamfp/aistock/infra/ls/LsEtfApiClient.java), [시장 조회 서비스](AI-STOCK_Backend/aistock/src/main/java/com/teamfp/aistock/domain/stock/service/MarketQueryService.java).

### 4.2 비회원 호가·실시간 데이터 공급

공개 GET 권한은 이미 열려 있다. 그러나 `/api/stocks/{stockCode}/hoga`는 Redis 캐시만 조회하며, 캐시가 없으면 503을 반환한다. 프론트 실시간 구독은 토큰이 없으면 연결하지 않는다. 다른 구독자 없이 비회원만 조회할 때 호가 데이터가 공급되는지는 별도 문제다.

현재가는 프론트에 REST 상세 조회 폴백이 있지만 호가에는 같은 폴백이 없다.

조치: 공개 호가 REST 조회 또는 인증된 내부 수집기의 공유 캐시 등 데이터 공급 경로를 추가한다. 개인 주문·알림 채널의 인증을 풀어서 해결하면 안 된다.

완료 기준: 다른 로그인 사용자가 없는 상태에서도 비회원 종목 상세에서 호가를 읽고, 공급 중단 시 명확한 안내를 받는다.

근거: [StockService](AI-STOCK_Backend/aistock/src/main/java/com/teamfp/aistock/domain/stock/service/StockService.java), [프론트 종목 API](AI-STOCK_Frontend/src/lib/api/stock.ts), [실시간 구독](AI-STOCK_Frontend/src/lib/api/realtime.ts).

### 4.3 “설문으로만 변경” 정책의 서버 적용

마이페이지의 성향·레벨 직접 선택 UI는 제거했고, 설문 제출 시 투자 레벨을 서버에서 계산한다. 하지만 기존 `PUT /api/users/me/investment-profile`과 `PATCH /api/users/me/profile`의 선택적 `investment` 입력은 여전히 직접 값을 갱신할 수 있다.

또한 설문 API의 투자·자금 성향은 `SurveyRequest`로 받은 값을 저장하고, 레벨만 `SurveyLevelEvaluator`에서 산출한다. 즉, **UI 변경은 완료됐지만 서버 전체에서 설문만 허용하는 제약은 아직 완성되지 않았다.**

조치:

- 기존 직접 갱신 API와 `investment` 입력을 제거하거나 새 정책에 맞게 제한한다.
- 투자·자금 성향도 검증된 답안으로 서버에서 산출한다.
- 프론트가 임의의 레벨·성향 값을 보내도 저장되지 않는 테스트를 추가한다.
- 기존 클라이언트 호환 여부와 저장된 프로필의 재평가 정책을 결정한다.

근거: [UserController](AI-STOCK_Backend/aistock/src/main/java/com/teamfp/aistock/domain/user/controller/UserController.java), [UserService](AI-STOCK_Backend/aistock/src/main/java/com/teamfp/aistock/domain/user/service/UserService.java), [레벨 평가](AI-STOCK_Backend/aistock/src/main/java/com/teamfp/aistock/domain/user/service/SurveyLevelEvaluator.java).

### 4.4 소셜 계정의 내 정보 수정·탈퇴

마이페이지의 수정 진입과 탈퇴 UI는 현재 비밀번호를 요구한다. `UserService.matchOrThrow()`는 저장 비밀번호가 없는 계정에 `PASSWORD_NOT_SET`을 반환한다. 소셜 로그인 설정을 완료한 후에는 이런 계정이 일반 계정과 같은 수정 흐름을 통과하지 못할 수 있다.

조치: 소셜 재인증 또는 검증된 이메일 기반 재인증 경로를 정의하고, 비밀번호 없는 계정용 수정·탈퇴 UI 및 백엔드 검증을 연결한다. 단순히 비밀번호 검사를 생략하는 방식은 피한다. 공급자별 실제 계정으로 통합 검증이 필요하다.

근거: [마이페이지](AI-STOCK_Frontend/src/features/my-page/components/MyPageDashboard.tsx), [탈퇴 UI](AI-STOCK_Frontend/src/features/my-page/components/profile/ProfileModals.tsx), [UserService](AI-STOCK_Backend/aistock/src/main/java/com/teamfp/aistock/domain/user/service/UserService.java).

### 4.5 홈 상승순·하락순의 범위

현재 홈은 선택 조건에 따라 가져온 목록을 프론트에서 정렬한다. 상승·하락 선택이 전체 시장 등락률 순위를 의미한다면, 현재 시가총액 상위 목록의 재정렬만으로는 요구를 충족하지 못한다.

백엔드에는 `GET /api/market/rankings?sort=change`가 있으나 홈에서는 별도 연결이 필요하다. 전체 하락률 순위는 해당 기준에 맞는 조회 계약이 추가로 필요할 수 있다. “현재 목록 정렬” 의도라면 UI에 범위를 표시하는 것으로 해결할 수 있으므로 **요구 정의 후 작업할 항목**이다.

근거: [StockTable](AI-STOCK_Frontend/src/features/home/components/StockTable.tsx), [MarketQueryService](AI-STOCK_Backend/aistock/src/main/java/com/teamfp/aistock/domain/stock/service/MarketQueryService.java).

## 5. 구현은 있지만 실제 운용 검증이 필요한 부분

### 5.1 뉴스 검색 채팅과 정기 브리핑

두 기능은 별도로 구현되어 있다. **뉴스 채팅 API가 없다는 설명은 더 이상 해당하지 않는다.**

| 구분 | 현재 구현 | 남은 검증·제약 |
|---|---|---|
| 뉴스 검색 채팅 | 기본 화면. `POST /api/ai/news/chat`에서 질문·최근 대화로 검색 조건을 정하고, 네이버 기사 검색 → Gemini 요약·근거 검증 → 출처 카드 반환 | 실제 공급자 연동으로 검색·후속 질문·요약 성공 여부 확인. 테스트는 외부 API를 대체한 단위 테스트 |
| 정기 브리핑 | 별도 탭. 언론사 설정, 과거 브리핑 조회, 저장·재무설계사 연결 구현 | 매일 07:00 KST에 서버가 실행 중이어야 하며 실제 생성·검증·DB 저장 성공은 미확인 |
| 선택 언론사 표시 | 저장된 언론사 배지, 변경 후 미저장 안내, 저장 응답 반영, 설정 독립 조회 구현 | 표시 기능은 추가 구현 대상에서 제외 |

정기 브리핑은 설정 저장 즉시 생성되지 않는다. 오전 7시에 서버가 꺼져 있으면 자동 보충 생성이 없고, 관련 기사 없음·AI 오류·근거 검증 실패 시 생성되지 않을 수 있다. **수동 정기 브리핑 생성 API와 버튼은 없다.** 새 뉴스 채팅 POST는 정기 브리핑 생성 API가 아니므로 혼동하지 않는다. 수동 생성은 필요할 때 추가할 기능이며 현재 UI 연결 누락은 아니다.

뉴스 검색 채팅의 대화는 현재 화면의 상태로 유지된다. 정기 브리핑 탭을 오가면 유지되지만 새로고침·화면 이탈 후 복원하는 서버 저장 기능은 없다. 대화 영구 보관이 필요하다면 별도 범위로 설계한다. 정기 브리핑에서 선택한 언론사는 **정기 생성용**이며 뉴스 검색 채팅의 언론사 필터로 연결된 것은 아니다.

조치: 실제 뉴스 검색과 AI 응답, 후속 질문의 검색 조건, 기사 없음·외부 실패, 정기 생성 시간·중복 방지·저장·조회까지 확인한다. 수동 생성과 채팅 기록 보관은 요구 확정 후 추가한다.

근거: [뉴스 채팅 UI](AI-STOCK_Frontend/src/features/ai-market-briefing/components/NewsChat.tsx), [정기 브리핑 UI](AI-STOCK_Frontend/src/features/ai-market-briefing/components/DailyBriefing.tsx), [NewsChatService](AI-STOCK_Backend/aistock/src/main/java/com/teamfp/aistock/domain/ai/service/NewsChatService.java), [AiNewsService](AI-STOCK_Backend/aistock/src/main/java/com/teamfp/aistock/domain/ai/service/AiNewsService.java).

### 5.2 AI 재무설계사·DART 재무자료

재무설계사의 채팅 입력·새 채팅·최근 상담 자동 선택·진단 후 상담 진입·모바일 상담 선택은 수정 완료다. 진단을 다시 해야만 상담할 수 있는 UI 작업은 남은 목록에서 제외한다. 기존 상담 세션/메시지·목표 계획·자료 연결 API도 존재한다.

`GEMINI_API_KEY`, `DART_API_KEY`는 설정되어 있지만, 이번 작업에서 실제 AI 응답 생성과 DART 재무조회 성공까지 검증하지 않았다. 구현 완료와 외부 서비스 정상 동작을 구분한다.

조치: 모델 권한·한도, 실제 상담 답변, 종목명·회사코드 매핑, 재무·분기·배당 자료 조회를 확인한다. LS 의존 상담 도구도 사용하는 TR별로 검증한다.

근거: [재무설계사 화면](AI-STOCK_Frontend/src/features/ai-financial-planner/components/AiFinancialPlanner.tsx), [상담 입력](AI-STOCK_Frontend/src/features/ai-financial-planner/components/PlannerChat.tsx).

### 5.3 관심 종목·주문·최근 본 종목의 검증 범위

이 세 기능은 이제 `StockQuoteService`를 통해 실시간 캐시가 없으면 LS REST 시세로 종목명·가격을 확인한다. 캐시만 조회해 실패하던 구현은 수정 완료다. 외부 REST 조회까지 실패하면 저장·주문이 실패할 수 있으며, 검증되지 않은 종목명이나 가격으로 강제 처리하지 않는다.

시장가는 서버 조회 가격으로 **모의 체결**하고, 지정가는 등록 후 실시간 시세가 조건을 만족해야 체결된다. 지정가 등록·취소 및 동결금 반환은 검증했지만 실제 LS 실시간 수신부터 지정가 체결까지의 전체 운용 검증은 남아 있다. 이번 작업을 실제 증권계좌 주문 연동 완료로 해석하지 않는다.

근거: [공통 시세 보완](AI-STOCK_Backend/aistock/src/main/java/com/teamfp/aistock/domain/stock/service/StockQuoteService.java), [주문 서비스](AI-STOCK_Backend/aistock/src/main/java/com/teamfp/aistock/domain/order/service/OrderService.java), [통합 테스트](AI-STOCK_Backend/aistock/src/test/java/com/teamfp/aistock/domain/order/service/TradingFallbackIntegrationTest.java).

## 6. 환경변수 점검표

아래는 파일에 값이 있는지 기준이다. 쉘 환경의 키 존재 여부도 일부 보조 확인했으며, 외부 서비스의 인증 성공 여부와는 다르다.

| 항목 | 현재 로컬 상태 | 비고 |
|---|---|---|
| `NEXT_PUBLIC_API_BASE_URL` | 프론트 `.env.local`에 설정 | `http://localhost:8080` |
| `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM` | 백엔드 `.env`에 없음 | 실제 메일 발송을 위해 설정 필요 |
| `MAIL_HOST`, `MAIL_PORT` | `.env`에 없음 | 개발 설정 기본값 존재; 부재 자체는 오류 아님 |
| `GOOGLE_OAUTH_*`, `NAVER_OAUTH_*`, `KAKAO_OAUTH_*` | `.env`에 없음 | 인증 시작 API 모두 미설정 503 확인 |
| `LS_APP_KEY`, `LS_APP_SECRET` | 값 존재, 토큰 발급 성공 | 12:19~12:21경 일부 시세 조회도 성공; 모든 TR·실시간 연결 검증 완료를 뜻하지 않음 |
| `LS_MODE`, `LS_WEBSOCKET_URL` | 값 존재 | 모드·접속 주소와 발급 키 환경 일치 확인 |
| `NAVER_CLIENT_ID`, `NAVER_CLIENT_SECRET` | 값 존재 | 뉴스 검색 API 200 확인; OAuth용 아님 |
| `GEMINI_API_KEY`, `DART_API_KEY` | 값 존재 | 실제 호출 성공 미검증 |
| `GEMINI_API_URL` | 값 존재, 현재 소스 참조 없음 | 현재는 `GEMINI_JUDGE_API_URL`, `GEMINI_ANSWER_API_URL`을 참조하며 기본값 존재 |
| `TAVILY_API_KEY` | 값 존재, 현재 소스 참조 없음 | 뉴스 검색은 네이버 구현 사용 |
| `DB_USERNAME`, `DB_PASSWORD`, `REDIS_HOST`, `REDIS_PORT` | 개발 기본값 사용 가능 | 이번 환경에서 DB·Redis 구동 확인; `.env`에 없다고 미설정 장애로 분류하지 않음 |
| `JWT_SECRET`, `ADMIN_SIGNUP_CODE` | `.env`에 없음 | 개발 JWT 기본값 존재. 기존 관리자 로그인과 관리자 신규가입 코드는 별개; 배포 전 명시 설정 검토 |

환경 예시 파일은 실제 참조 변수만 포함하도록 정리하고, 미사용 변수 제거는 사용처 재확인 후 진행한다. 현재 `.env`의 내용을 그대로 문서나 저장소에 복사하지 않는다.

## 7. 완료 항목 — 남은 구현 목록에서 제외

### 7.1 이번 수정에서 완료한 작업

| 기능 | 완료된 변경 | 확인 수준 |
|---|---|---|
| LS 조회 지연 | 토큰 만료 전 재사용, 동시 발급 제한, LS 연결 1초·응답 대기 3초, 공개 시세 응답 캐시 | LS 관련 테스트 통과. 당시 상세 시세 0.298초 → 즉시 재조회 0.009초, 거래량 순위 0.451초 → 0.008초 측정; 항상 같은 속도를 보장하지 않음 |
| 홈 시세 표시 | 지수·환율이 서로 기다리지 않고 응답대로 표시 | 타입·린트 검사 통과 |
| 관심 종목 | 캐시 없을 때 REST 보완, 사이드바 하트의 등록·해제 API 연결, 메인 목록과 동기화, 오류 안내 | 서비스 테스트 및 DB 통합 테스트 통과 |
| 주문 | 지정가·시장가 드롭다운, 서버 시세 보완, 시장가 서버 가격 적용, 예약 매도 수량·금액 범위 검사, 주문 처리와 후속 목록 갱신 실패 구분 | 주문 관련 단위·DB 통합 테스트 통과. 실제 외부 시세는 테스트 대역 사용 |
| 사이드바 종목 이동 | 관심·최근 본·보유 종목 링크 연결, 하트는 관심 변경만 수행 | 타입·린트 검사 통과 |
| 최근 본 종목 | 최초 저장 시 REST 보완, 재방문 시간 갱신, 저장 후 목록 자동 갱신, 저장 실패 표시 | 최초 저장·중복 방지 단위/DB 통합 테스트 통과 |
| 종목 리서치 한국어 | 투자의견·배당 필드명, PER/PBR 명칭, 주요 영문 투자의견 값 번역 | 타입·린트 검사 통과 |
| AI 재무설계사 | 새 채팅, 최근 상담 자동 선택, 진단 완료 후 상담 진입, 입력창 높이·모바일 화면 개선 | 타입·린트 검사 통과; 실제 AI 답변은 별도 검증 |
| 맞춤 시황 뉴스 채팅 | 질문·후속 대화 기반 검색, 기사 요약·근거 검증, 기사 출처 카드, 정기 브리핑 탭 분리 | 외부 API 대역을 사용하는 테스트 4개 통과, 실행 서버 OpenAPI에서 `/api/ai/news/chat` 등록 확인 |
| 정기 브리핑 언론사 표시 | 현재 저장된 언론사 배지, 미저장 선택 안내, 저장값 갱신, 다른 조회 실패와 분리 | 타입·린트 검사 통과 |

시세 캐시 유효기간은 현재가·지수·ETF 현재가 2초, 순위 10초, 기간별주가 60초다. 실패 응답·만료 데이터는 재사용하지 않는다. 전체 API는 여러 외부 호출이 이어질 수 있으므로 총 소요시간이 3초로 제한된다는 뜻은 아니다.

### 7.2 기존에 구현된 API

다음은 프론트 호출과 대응 백엔드 경로를 확인했다. 모든 기능을 실제 계정으로 끝까지 테스트했다는 뜻은 아니다.

| 기능 | 대응 경로 / 상태 |
|---|---|
| 공통 로그인·관리자 역할 분기 | `/api/auth/login`, `/api/users/me` 존재, 관리자 로그인 검증 이력 있음 |
| 이메일 인증·복구 | `/api/auth/email/*`, `/api/auth/find-id`, `/api/auth/password/reset` 존재 |
| 내 정보·비밀번호·탈퇴 | `/api/users/me`, `/api/users/me/profile`, `/api/users/me/password*` 존재 |
| 투자 설문·레벨 산출 | `/api/users/me/survey` 존재; 직접 갱신 경로 제한은 별도 과제 |
| 공개 뉴스·시장·종목 조회 | `/api/market/*`, `/api/stocks/*` GET 공개; 데이터 공급 장애와 구분 |
| 관심종목·최근 본 종목 | `/api/watchlist`, `/api/recent-viewed` 존재; 캐시 미스 보완과 화면 갱신 완료 |
| 계좌·주문·보유·손익 | `/api/accounts`, `/api/orders`, `/api/orders/holdings`, 계좌별 profit/returns 존재; 지정가·시장가 선택 연결 완료 |
| 충전 요청·심사 | `/api/accounts/{id}/charge-requests`, `/api/admin/charge-requests/*` 존재 |
| AI 상담·목표 저장·자료 연결 | `/api/ai/planning/*`, `/api/goal-plans/*` 존재 |
| 뉴스 검색 채팅·정기 브리핑 | `/api/ai/news/chat`, `/api/ai/news/settings`, `/api/ai/news/briefings*` 존재; 즉시 정기 생성 API와는 별개 |
| 알림 조회·읽음 | `/api/notifications`, `/api/notifications/{id}/read` 존재 |
| 관리자 대시보드·통계 | `/api/admin/dashboard`, `/api/admin/statistics/*` 존재; 화면 데이터 조회 검증 이력 있음 |
| 회원 정지 사유·기간·해제 | `/api/admin/users/{id}/status` 및 자동 만료 처리 구현; 로컬 DB 컬럼 반영 완료 |

관리자 문의·감사 로그 등 **백엔드는 있는데 현재 관리자 메뉴에 없는 기능**은 반대 방향의 작업이므로 본 미구현 목록에서 제외했다. “시가총액 상위 비교” 탭도 실제로 상위 시가총액 목록을 표시하는 구현이 있으므로, 유사 업종 비교 기능이 없다는 이유로 현재 탭을 미구현으로 분류하지 않았다.

## 8. 확인 방법 및 완료 체크리스트

소스 대조: 프론트 `src/lib/api`, 화면 내 직접 호출, Spring 컨트롤러·서비스, `application*.yml`, 환경변수 이름. 실행 API 목록은 로컬 `/v3/api-docs`와 대조했다. 동적 경로와 업무 로직은 관련 소스를 읽어 확인했으며 전체 기능의 자동 계약 테스트를 수행한 것은 아니다.

앞선 작업에서 직접 확인한 실행 결과(이번 문서 갱신 시 재호출하지 않음):

- Google·네이버·카카오 authorize: 503 / 공급자 설정 미완료
- 공개 뉴스: 200 / 성공
- 종목 호가: 503 / 현재가 정보 일시 조회 불가
- LS 재확인(12:19~12:21경): 토큰 발급 성공, 백엔드 삼성전자 상세 조회 200, ETF `t1901` 정상 가격 반환, `t1904` 정상 코드·빈 구성종목 목록. 호가는 계속 503이며 상세 내용은 4.1 참고
- OpenAPI 문서: 조회 가능

다음 작업 순서:

- [ ] SMTP 환경설정 → 실제 메일 수신 → 회원가입·아이디 찾기·비밀번호 재설정 검증
- [ ] OAuth 앱·콜백·인증정보 설정 → 공급자별 실제 로그인 검증
- [ ] LS 순위·지수·차트 및 ETF 구성종목 데이터 검증, 호가 실시간 수신·캐시 공급 경로 확인
- [ ] ETF `t1901`·`t1904` 신규 입력 필드 `exchgubun` 반영 및 회귀 검증
- [ ] 외부 장애를 정상 빈 목록과 구분하는 API 응답·UI 안내 개선
- [ ] 비회원 호가 데이터 공급 경로 보완
- [ ] 설문 외 직접 성향/레벨 변경 경로 제한 및 성향 서버 산출
- [ ] 소셜 계정의 정보 수정·탈퇴 재인증 흐름 정의
- [ ] 뉴스 검색 채팅·후속 질문·출처 표시를 실제 Gemini·네이버 연동으로 검증
- [ ] 오전 7시 정기 브리핑 생성·근거 검증·DB 저장·조회 검증
- [ ] 재무설계사 실제 AI 답변 및 DART 자료 연동 검증
- [ ] 실제 LS 시세 수신에 의한 지정가 체결·잔고·알림 검증
- [ ] 상승·하락 순위 범위 확정 및 연결
- [ ] 환경 예시 파일 정리 및 배포 설정 문서화

선택적으로 범위를 결정할 항목: 수동 정기 브리핑 생성, 뉴스 채팅 기록의 서버 보관, 뉴스 채팅에 정기 브리핑 언론사 설정 적용. 현재 동작과 요구 차이를 확인한 뒤 추가하며 필수 미구현 항목으로 단정하지 않는다.

이번 문서 갱신에서는 애플리케이션 코드·환경값을 변경하지 않았고 테스트를 재실행하지 않았다. 앞선 변경 단계에서 주문·관심 종목 관련 48개 테스트, 최근 본 종목 관련 단위/통합 테스트 4개, 뉴스 채팅 테스트 4개가 각각 통과했다. 서로 다른 실행 시점의 결과이며 전체 프로젝트 테스트를 한 번에 통과했다는 의미는 아니다. DB 통합 검증은 임시 사용자·계좌와 대체 외부 API를 사용했고 데이터는 롤백했다.
