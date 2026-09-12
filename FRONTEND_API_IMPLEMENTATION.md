# 프론트 미연동 기능 구현 보고서

작성일: 2026-09-09  
대상: AI-STOCK_Frontend / AI-STOCK_Backend

## 1. 요약

프론트의 실행 가능한 주요 화면을 점검하여 **없던 API를 추가하고, 기존 API가 있는데 연결되지 않았던 화면도 연결**했습니다. 저장 기능은 DB에 기록되며, 성공·오류·로딩·빈 목록 상태를 표시합니다.

다만 **외부 서비스까지 모두 실동작 확인이 끝난 것은 아닙니다.** 현재 로컬 서버에는 LS 시장 데이터 및 OAuth 제공자 설정이 없고, 뉴스 검색도 외부 연동 오류를 반환합니다. 이메일 발송과 Gemini 답변은 실제 제공자 인증정보를 넣은 뒤 추가 검증해야 합니다. 키 없이 임시 사용자를 만들거나 샘플 숫자를 실제 데이터처럼 표시하지 않습니다.

테스트 접속:

- 프론트: http://localhost:3000
- 백엔드: http://localhost:8080
- API 명세: http://localhost:8080/swagger-ui/index.html

## 2. 화면별 구현 내용

| 화면 | 구현·연결한 기능 | 구분 |
| --- | --- | --- |
| 회원가입 | 아이디 중복 확인, 입력 변경 시 재확인, 확인 전 다음 단계 제한, 투자 경험 저장 | API 추가 + UI 연결 |
| 아이디 찾기 | 이름·생년월일·이메일·인증코드 검증 후 실제 아이디 반환 | API 추가 |
| 비밀번호 찾기 | 이름·아이디·이메일·인증코드 검증 후 BCrypt 비밀번호 변경, refresh token 폐기 | API 추가 |
| 소셜 로그인 | Google·Naver·Kakao 인가 URL, 제공자 토큰 교환 및 사용자 조회, 콜백 화면 | 임시 인증 제거 + 실제 연동 |
| 마이페이지 | 실제 생년월일 조회/수정, 투자성향·자금성향·투자 경험 조회/저장 | API 추가·확장 |
| 회원정보 수정 | 현재 비밀번호 확인 후 개인정보·성향·선택적 새 비밀번호를 단일 DB 트랜잭션으로 저장 | 통합 API 추가 |
| 회원 탈퇴 | 현재 비밀번호 재검증, 마지막 관리자 보호, 본인 관련 자료 삭제 및 회원정보 익명화 | API 추가 |
| 가상캐시 충전 | 실제 충전 요청 접수·요청 이력·승인/거절 상태 연결, 중복 제출 방지 | 기존 API 연결 |
| 주문 내역 | 실제 주문 목록 표시, 데이터 없는 경우 샘플 주문 대신 빈 상태 | 기존 API 연결 |
| 수익률 | 체결 이력을 재생하여 매도 시점 평균 매입단가와 실현손익 계산 | API 추가 |
| AI 재무설계사 | 상담 세션 생성·목록·선택·대화 이력·질문 전송·실제 답변·오류 표시 | 기존 API 연결 |
| 투자성향 설문 | 응답 저장 및 실제 저장 결과 표시, 고정 사용자명·고정 진단 문구 제거 | 기존 API 연결 |
| AI 데이터 연동 설정 | 저장한 브리핑/목표 선택 영속화, 본인 자료만 허용, AI 상담 참고문맥 반영 | API 추가 |
| 목표 도달 시뮬레이션 | 적립식 복리 계산, 실행 기록 저장, 목록 불러오기, 북마크, 삭제 API | API 추가 |
| AI 시황 브리핑 | 언론사 설정, 실제 브리핑 이력/본문/근거 기사 조회, 북마크 | API 추가·기존 연결 |
| 홈 | 실제 지수·환율·주요 종목·뉴스 조회, 종목명/코드 검색 | API 추가 |
| 뉴스 리포트 | 검색어·분류별 실제 기사 조회 및 원문 링크 | API 추가 |
| 종목 상세 | 실제 과거 OHLC 차트, 기간/주기 변경, 실시간 현재가·호가 구독, 실제 체결 수신 목록 | API 추가·STOMP 연결 |
| 종목 리서치 | 시세 상세, 재무, 최근 실적, 배당, 시총 상위 비교, 투자 의견 조회 | API 추가 |
| 종목 알림 | 본인 알림 목록 및 읽음 처리 | 기존 API 연결 |
| 관리자 | 실제 관리자 로그인/권한 확인, 요약 지표, 회원·거래·충전 요청 목록/검색/필터/페이징/상세, 회원 정지/활성화, 주문 취소, 충전 승인/거절 | 기존 API 연결 |

프론트의 관심종목·최근 본 종목·모의 주문 등 이미 연동된 기능은 유지했습니다. 주문 이후에는 계좌 잔액도 다시 조회하도록 보완했습니다. 종목코드 변경 시 이전 종목의 주문 상태가 남지 않도록 상세 화면을 초기화합니다.

## 3. 새 API / 확장 API

인증 관련 공개 API를 제외한 아래 API는 Bearer JWT가 필요합니다. userId는 요청값이 아니라 인증정보에서 가져옵니다.

### 회원/인증

| 메서드 | 경로 | 용도 |
| --- | --- | --- |
| GET | /api/auth/login-id/availability?loginId= | 아이디 중복 확인 |
| POST | /api/auth/find-id | 이메일 인증 기반 아이디 찾기 |
| POST | /api/auth/password/reset | 이메일 인증 기반 비밀번호 재설정 |
| GET | /api/auth/oauth/{GOOGLE\|NAVER\|KAKAO}/authorize | 실제 제공자 인가 URL 및 state |
| POST | /api/auth/oauth/login | 기존 API 확장: provider, code, state 검증 후 로그인 |
| GET | /api/users/me/investment-profile | 투자 프로필 조회 |
| PUT | /api/users/me/investment-profile | 투자 프로필 변경 |
| PATCH | /api/users/me/profile | 현재 비밀번호 검증 + 회원정보/성향/비밀번호 통합 변경 |
| DELETE | /api/users/me | 현재 비밀번호 검증 후 탈퇴 |

기존 GET/PATCH /api/users/me에 birthdate를 반영했습니다. 기존 회원가입 요청에는 선택적 investmentLevel을 추가했습니다. 투자 경험 enum은 서버의 BEGINNER / INTERMEDIATE / EXPERT에 맞췄습니다.

통합 회원정보 요청 구조:

```json
{
  "currentPassword": "<현재 비밀번호>",
  "user": { "name": "<이름>", "email": "<이메일>", "birthdate": "2000-01-01" },
  "investment": {
    "investmentTendency": 3,
    "fundTendency": 2,
    "investmentLevel": "BEGINNER"
  },
  "passwordChange": {
    "currentPassword": "<현재 비밀번호>",
    "newPassword": "<새 비밀번호>"
  }
}
```

비밀번호를 바꾸지 않으면 passwordChange는 생략합니다.

### 목표·AI·손익

| 메서드 | 경로 | 용도 |
| --- | --- | --- |
| POST | /api/goal-plans | 목표 계산 및 실행 이력 저장 |
| GET | /api/goal-plans | 본인 최근 100개 이력 조회 |
| PATCH | /api/goal-plans/{planId}/saved | 본인 목표 북마크 |
| DELETE | /api/goal-plans/{planId} | 본인 목표 삭제 |
| GET | /api/ai/planning/preferences | 북마크·연결 자료 선택 조회 |
| PUT | /api/ai/planning/preferences | 본인 자료 검증 후 선택 저장 |
| GET | /api/ai/news/briefings | 본인 최근 100개 브리핑 조회 |
| GET | /api/ai/news/briefings/{date} | 날짜별 본인 브리핑 조회 |
| GET | /api/accounts/{accountId}/returns | 해당 본인 계좌의 매도별 실현손익 |

목표 요청: goal(retirement 또는 house), monthlyPayment(10만원~500만원), years(1~50), annualReturn(0~12), aggressive.

### 시장 데이터

| 메서드 | 경로 | 용도 |
| --- | --- | --- |
| GET | /api/market/search?query= | 종목명→종목코드 또는 6자리 코드 |
| GET | /api/market/indexes | KOSPI/KOSDAQ 지수 |
| GET | /api/market/exchange-rate | USD/KRW 환율 |
| GET | /api/market/rankings?sort= | volume/value/change/market-cap 상위 종목 |
| GET | /api/market/stocks/{code}/history?months= | 1~60개월 과거 시세 |
| GET | /api/market/stocks/{code}/detail | 시세·기업 지표 |
| GET | /api/market/stocks/{code}/research?section= | finance/earnings/dividend/peers/analysts |
| GET | /api/market/news?query= | 실제 뉴스 검색 |

기존 infra의 LS·DART·Naver 클라이언트를 재사용했습니다. 실시간은 기존 /ws-stomp의 /topic/stock/{code}, /topic/stock/{code}/hoga를 구독하며 화면 종료 시 연결과 재시도 타이머를 정리합니다.

## 4. 보안·데이터 처리

- 이메일 인증코드는 원자적으로 검증 후 삭제합니다. 실패는 이메일별 10분 동안 5회까지 허용합니다.
- 회원가입용 이메일 인증 완료 마커도 원자적으로 소비합니다.
- 아이디 찾기/비밀번호 재설정은 이메일 소유 증명을 먼저 확인하며, 이름 등 계정 정보가 일치해야 합니다.
- 현재 비밀번호 확인의 401 응답을 토큰 만료로 오인하지 않도록 해당 요청은 자동 토큰 재발급을 하지 않습니다.
- 목표·브리핑 연결은 본인 소유 자료만 허용합니다.
- 탈퇴는 자식 자료 정리 후 회원 개인정보를 익명화합니다. 기존 감사 로그는 유지합니다. DB 커밋 이후 refresh token·미체결 주문 캐시·관심종목 구독을 정리합니다.
- 소셜 로그인은 실제 제공자 응답의 ID와 이메일을 사용합니다. 임의 code로 사용자 ID를 만들어 내던 코드는 제거했습니다.
- OAuth state는 서버 세션과 브라우저 sessionStorage에 연결하고 10분 만료·1회 소비를 적용했습니다. 콜백 URL에서 인증코드를 제거합니다.
- OAuth 키·토큰·인가 코드를 문서나 로그에 출력하지 않습니다.
- 계좌 충전은 모의투자용 가상캐시이며 실제 금융거래가 아닙니다.

## 5. DB 및 실행

추가 테이블:

- goal_plans: 사용자별 적립식 목표 입력, 계산 이력, 북마크
- planning_preferences: 사용자별 브리핑 북마크와 AI 연결 자료 선택

schema.sql과 NAMING.md, redis-logic.md를 함께 갱신했습니다. 기존 DB용 추가 스크립트는 **frontend_api_migration.sql**입니다. 전체 schema.sql을 기존 DB에 다시 실행하지 마세요.

현재 dev 프로필의 ddl-auto=update로 신규 테이블이 반영되었습니다. 운영에서는 별도 마이그레이션 검토·적용이 필요합니다.

```powershell
# 백엔드: AI-STOCK_Backend/aistock
.\gradlew.bat bootRun

# 프론트: AI-STOCK_Frontend
npm run dev
```

로컬 실행에서는 `aistock/.env`를 `application.yml`이 선택적으로 읽습니다. 운영 환경에서는 파일 대신 배포 환경의 비밀 변수 주입을 권장합니다.

## 6. 외부 서비스 설정 및 미검증 범위

| 기능 | 필요한 설정 | 현재 검증 상태 |
| --- | --- | --- |
| LS 시세·차트·환율 | LS_APP_KEY, LS_APP_SECRET | 미설정 안내 HTTP 503 확인 |
| LS 실시간 | 위 키 + LS_MODE=real, 계정에 맞는 LS_WEBSOCKET_URL | 기본 mock 모드는 실제 스트림을 생성하지 않음. 실체결 수신 검증 미완료 |
| DART 재무·종목명 검색 | DART_API_KEY | 실제 제공자 응답 검증 미완료 |
| Naver 뉴스 | NAVER_CLIENT_ID, NAVER_CLIENT_SECRET, 필요 시 NAVER_NEWS_API_URL | 현재 호출 HTTP 502. 키·상품 권한·엔드포인트 확인 필요 |
| Gemini 상담·브리핑 생성 | GEMINI_API_KEY 및 사용 가능한 모델 URL | 실제 생성 답변 검증 미완료 |
| 인증 이메일 | MAIL_HOST, MAIL_PORT, MAIL_USERNAME, MAIL_PASSWORD, MAIL_FROM | 실제 메일 수신 검증 미완료 |
| Google 로그인 | GOOGLE_OAUTH_CLIENT_ID / CLIENT_SECRET / REDIRECT_URI | 미설정 HTTP 503 확인 |
| Naver 로그인 | NAVER_OAUTH_CLIENT_ID / CLIENT_SECRET / REDIRECT_URI | 실제 제공자 로그인 미검증 |
| Kakao 로그인 | KAKAO_OAUTH_CLIENT_ID / REDIRECT_URI, 제공자 설정 시 CLIENT_SECRET | 실제 제공자 로그인 미검증 |

OAuth 환경변수의 생략 표기는 모두 같은 접두사를 붙입니다. 예: GOOGLE_OAUTH_CLIENT_SECRET, GOOGLE_OAUTH_REDIRECT_URI. Naver 뉴스 검색 키와 Naver 로그인 키는 별도 설정입니다.

제공자 콘솔에 등록할 로컬 콜백:

- Google: http://localhost:3000/oauth/callback/google
- Naver: http://localhost:3000/oauth/callback/naver
- Kakao: http://localhost:3000/oauth/callback/kakao

프론트 API 주소는 NEXT_PUBLIC_API_BASE_URL, 서버 CORS는 CORS_ALLOWED_ORIGINS로 맞춥니다. OAuth 요청은 쿠키를 포함하므로 운영의 프론트/API가 서로 다른 사이트라면 HTTPS·세션 쿠키 SameSite/Secure 설정도 확인해야 합니다. 서버를 여러 대 운영하면 OAuth 세션 공유 또는 세션 고정 라우팅이 필요합니다.

소셜 전용 계정은 기존 설계상 비밀번호가 없습니다. 따라서 현재 비밀번호를 요구하는 정보 수정/탈퇴 화면은 PASSWORD_NOT_SET으로 차단합니다. 소셜 재인증 기반 계정 관리 흐름은 별도 보완 대상입니다.

### 데이터 의미와 제공 범위

- 목표 계산은 **매월 말 납입, 고정 월복리** 가정입니다. 세금·수수료·물가상승·실제 수익 보장을 포함하지 않습니다. 계산 결과에 고정 투자 추천을 붙이던 AI 인사이트는 계산 해설로 교체했습니다.
- 설문은 손실 허용 질문 응답을 투자성향 1~5로, 목적을 자산증식→수익추구형 / 생활비→자유소비형 / 채무상환→목표달성형으로 연결합니다. 8개 답변은 모두 저장하며 전문 적합성 평가로 표시하지 않습니다.
- 실현손익은 기존 보유종목 처리와 동일한 정수 평균 매입단가 기준입니다. 아직 실제 원장에 없는 배당·이자·수수료 수익은 만들어 표시하지 않습니다.
- 홈 주요 종목은 기존 LS 클라이언트가 반환하는 상위 최대 10개입니다. 상승순/하락순은 해당 목록 안의 정렬이며 시장 전체 급등락 순위로 표시하지 않습니다.
- peers 응답은 기존 제공 가능한 시가총액 상위 비교이며 동일 업종 경쟁사 추정으로 표시하지 않습니다.
- 브리핑은 기존 정책대로 매일 오전 7시(KST)에 정기 생성합니다. 새 사용자에게 아직 생성물이 없으면 빈 상태를 표시하며, 이번 작업에서 즉시 생성 버튼/API는 추가하지 않았습니다.
- 과거 시세 주/월/년 봉은 조회된 일봉을 집계합니다. 거래량/체결 목록은 실수신 데이터만 사용합니다.
- 기존 LS infra 일부는 제공자 실패를 빈 목록으로 반환합니다. 미설정 키는 새 API에서 명시적으로 차단했지만, 설정 후 빈 응답은 제공자 상태까지 확인해야 합니다.
- 기존 샘플 뉴스 상세 주소는 실제 뉴스 검색 화면으로 안내합니다. 기사 전문을 복제하지 않고 원문 링크를 제공합니다.
- 푸터 약관 링크 등 정적 콘텐츠와 API 연동 무관한 시안 문구는 이번 백엔드 구현 범위와 구분했습니다.

## 7. 검증 결과

### 자동 검증

- 프론트 npm run build: 성공(프로덕션 빌드).
- 프론트 npx tsc --noEmit: 성공.
- 프론트 npm run lint: 오류 0건, 기존 로고 img 관련 경고 2건.
- 백엔드 전체 테스트: 413개 중 406개 통과, 7개 스킵, 실패/오류 0건.
- 이후 추가한 UserWithdrawalIntegrationTest: 1개 통과. 실제 DB에서 임시 사용자·계좌·목표 삭제 및 익명화 검증 후 트랜잭션 롤백.

추가 테스트는 복리 계산, 다른 사용자 목표 저장/삭제 차단, 연결 자료 소유권, 평균 매입가 및 부분 매도 손익, 이메일 인증 실패 차단, 비밀번호 재설정·refresh 폐기, 아이디 중복 검사, OAuth 미설정 차단과 state 재사용/만료를 확인합니다.

기존 OrderServiceIntegrationTest는 원래부터 일부 테스트 데이터를 커밋해 남기는 방식입니다. 전체 테스트는 반드시 개발용 DB에서 실행해야 합니다. 이번 신규 탈퇴 통합 테스트는 롤백 방식입니다.

### 실행 중인 서버 확인

- 실제 로그인 성공.
- 기존 아이디 fnel2003 중복 확인: HTTP 200, available=false.
- 본인 정보/성향/목표/상담 세션/브리핑/연동 설정/알림 조회: HTTP 200.
- 본인 계좌 실현손익: HTTP 200.
- 목표 생성 → 0% 수익률에서 원금 12,000,000원 확인 → 저장 → 목록 재조회 → 테스트 목표 삭제: 성공.
- 인증 없는 목표 조회: HTTP 401.
- 일반 사용자 관리자 화면 API: HTTP 403.
- 잘못된 목표 계산 입력: HTTP 400.
- 최종 LS 미설정 응답: HTTP 503.
- Naver 뉴스 외부 연동 실패: HTTP 502.
- OAuth 미설정 응답: HTTP 503.

사용자 계정 비밀번호·잔액·보유종목을 검증 목적으로 변경하지 않았습니다. 직접 만든 임시 목표 기록은 삭제했습니다.

### 브라우저 확인

- 실제 계정 로그인 후 홈 이동.
- 마이페이지 실제 계정정보 조회.
- 목표 시뮬레이션 화면과 DB 기반 저장 목록의 빈 상태 확인.
- 모든 외부 서비스 정상 응답, 관리자 승인 작업, 실제 이메일 복구, 소셜 제공자 로그인, 실시간 장중 시세에 대한 종단간 검증은 미완료입니다.

## 8. 다음 확인 순서

1. 위 외부 서비스 키·상품 권한·콜백 URL을 설정합니다.
2. 이메일 인증 발송/수신 → 아이디 찾기/비밀번호 재설정을 테스트용 계정으로 검증합니다.
3. 시장 데이터와 장중 현재가·호가 수신을 확인합니다.
4. Gemini 상담 및 오전 7시 브리핑 생성 결과를 확인합니다.
5. 별도 관리자 테스트 계정에서 가상캐시 승인·거절·회원 상태 변경을 검증합니다.
6. 운영 전 소셜 전용 계정의 재인증 기반 정보 수정/탈퇴 흐름을 보완합니다.

## 참고한 제공자 명세

- [Google OAuth 2.0 웹 서버 흐름](https://developers.google.com/identity/protocols/oauth2/web-server)
- [Naver 로그인 API](https://developers.naver.com/docs/login/api/api.md)
- [Kakao 로그인 REST API](https://developers.kakao.com/docs/ko/kakaologin/rest-api)
