package com.teamfp.aistock.domain.ai.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.teamfp.aistock.domain.account.dto.response.AccountInfoResponse;
import com.teamfp.aistock.domain.account.entity.AccountStatus;
import com.teamfp.aistock.domain.account.service.AccountService;
import com.teamfp.aistock.domain.ai.dto.request.AiChatRequest;
import com.teamfp.aistock.domain.ai.dto.response.AiChatResponse;
import com.teamfp.aistock.domain.ai.entity.AiPlanningSession;
import com.teamfp.aistock.domain.ai.repository.AiPlanningMessageRepository;
import com.teamfp.aistock.domain.ai.repository.AiPlanningSessionRepository;
import com.teamfp.aistock.domain.order.service.HoldingValuationService;
import com.teamfp.aistock.domain.user.entity.Role;
import com.teamfp.aistock.domain.user.entity.User;
import com.teamfp.aistock.domain.user.repository.InvestmentProfileRepository;
import com.teamfp.aistock.domain.user.repository.UserRepository;
import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;
import com.teamfp.aistock.global.redis.RedisAiToolCacheService;
import com.teamfp.aistock.global.redis.RedisRateLimiterService;
import com.teamfp.aistock.infra.dart.DartApiClient;
import com.teamfp.aistock.infra.dart.dto.DartFinancialResponse;
import com.teamfp.aistock.infra.gemini.GeminiApiClient;
import com.teamfp.aistock.infra.gemini.dto.GeminiRequest;
import com.teamfp.aistock.infra.gemini.dto.GeminiResponse;
import com.teamfp.aistock.infra.ls.LsHighItemApiClient;
import com.teamfp.aistock.infra.ls.LsInvestInfoApiClient;
import com.teamfp.aistock.infra.ls.LsInvestorTrendApiClient;
import com.teamfp.aistock.infra.ls.LsMarketDataApiClient;
import com.teamfp.aistock.infra.ls.LsSectorApiClient;
import com.teamfp.aistock.infra.ls.dto.LsFinancialRankingDto;
import com.teamfp.aistock.infra.ls.dto.LsForeignInstitutionalTrendDto;
import com.teamfp.aistock.infra.ls.dto.LsInvestmentOpinionDto;
import com.teamfp.aistock.infra.ls.dto.LsMarketLiquidityDto;
import com.teamfp.aistock.infra.ls.dto.LsOverseasIndexDto;
import com.teamfp.aistock.infra.ls.dto.LsRankingItemDto;
import com.teamfp.aistock.infra.ls.dto.LsShareholderMeetingDto;
import com.teamfp.aistock.infra.ls.dto.LsThemeConstituentDto;
import com.teamfp.aistock.infra.ls.dto.LsThemeDto;
import com.teamfp.aistock.infra.naver.NaverNewsApiClient;
import com.teamfp.aistock.infra.naver.dto.NaverNewsSearchRequest;
import com.teamfp.aistock.infra.naver.dto.NaverNewsSearchResponse;
import com.teamfp.aistock.infra.naver.dto.NaverNewsSearchResponse.NaverNewsResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * feature/ai-planning — AiPlanningService 단위 테스트.
 *
 * 2026-07-31 코드리뷰 후속 수정 사항이 실제로 지켜지는지 검증한다: rate-limit increment 호출
 * 순서, Gemini 실패 시 사용자 메시지가 남지 않는지(orphan 메시지 방지), 대표 계좌를
 * AccountService를 통해서만 조회하는지, 세션 소유권 검증, 세션 제목이 최초 1회만 채워지는지.
 */
@ExtendWith(MockitoExtension.class)
class AiPlanningServiceTest {

    @Mock
    private AiPlanningSessionRepository sessionRepository;
    @Mock
    private AiPlanningMessageRepository messageRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private InvestmentProfileRepository investmentProfileRepository;
    @Mock
    private AccountService accountService;
    @Mock
    private HoldingValuationService holdingValuationService;
    @Mock
    private RedisRateLimiterService rateLimiterService;
    @Mock
    private RedisAiToolCacheService aiToolCacheService;
    @Mock
    private GeminiApiClient geminiApiClient;
    @Mock
    private DartApiClient dartApiClient;
    @Mock
    private NaverNewsApiClient naverNewsApiClient;
    @Mock
    private LsMarketDataApiClient lsMarketDataApiClient;
    @Mock
    private LsInvestorTrendApiClient lsInvestorTrendApiClient;
    @Mock
    private LsInvestInfoApiClient lsInvestInfoApiClient;
    @Mock
    private LsHighItemApiClient lsHighItemApiClient;
    @Mock
    private LsSectorApiClient lsSectorApiClient;
    @Mock
    private com.teamfp.aistock.infra.ls.LsEtfApiClient lsEtfApiClient;
    @Mock
    private com.teamfp.aistock.infra.ls.LsProgramApiClient lsProgramApiClient;
    @Mock
    private com.teamfp.aistock.infra.ls.LsInvestorApiClient lsInvestorApiClient;
    @Mock
    private com.teamfp.aistock.infra.ls.LsEtcApiClient lsEtcApiClient;
    @Mock
    private com.teamfp.aistock.infra.ls.LsIndustryApiClient lsIndustryApiClient;

    @InjectMocks
    private AiPlanningService aiPlanningService;

    private static final Long USER_ID = 1L;
    private static final Long SESSION_ID = 10L;
    private static final Long ACCOUNT_ID = 100L;

    private AiPlanningSession session;

    @BeforeEach
    void setUp() {
        User user = User.builder()
                .loginId("tester")
                .name("테스터")
                .role(Role.USER)
                .isActive(true)
                .build();

        session = AiPlanningSession.builder()
                .user(user)
                .build();

        // 운영 코드에서는 Spring이 @Lazy 프록시를 self 필드에 주입해주지만, @InjectMocks로 만든
        // 이 테스트 인스턴스는 스프링 컨테이너 없이 생성되므로 self가 null로 남는다.
        // sendMessage()가 self.loadHistory()/self.saveTurn()을 올바르게 호출하는지만 검증하면
        // 되므로(AOP 프록시 동작 자체는 검증 대상이 아니다), 자기 자신을 그대로 넣어준다
        // (OrderExecutionServiceTest와 동일한 패턴).
        ReflectionTestUtils.setField(aiPlanningService, "self", aiPlanningService);

        // 운영 코드에서는 Spring이 AsyncConfig.aiToolTaskExecutor() 빈(스레드 풀)을 주입하지만,
        // 이 테스트는 병렬 실행 자체가 아니라 도구 호출 결과를 검증하는 게 목적이라 호출 스레드에서
        // 그대로 실행하는 동기 Executor를 넣는다 — 실제 스레드 풀을 쓰면 Mockito stub이 테스트
        // 스레드가 아닌 다른 스레드에서 호출돼 검증이 불안정해질 수 있다.
        ReflectionTestUtils.setField(aiPlanningService, "aiToolTaskExecutor", (Executor) Runnable::run);

        // rate-limit/세션 소유권 검증처럼 Gemini 호출까지 가지 않고 일찍 끝나는 테스트에서는
        // 이 스텁이 실제로 쓰이지 않는다 — lenient()로 STRICT_STUBS의 UnnecessaryStubbingException을 피한다.
        lenient().when(naverNewsApiClient.search(any())).thenReturn(new NaverNewsSearchResponse(List.of()));
        // 캐시 미스를 기본값으로 둔다 — 캐시 재사용 자체를 검증하는 테스트에서만 별도로
        // getCachedResult()가 값을 반환하도록 덮어써서 쓴다.
        lenient().when(aiToolCacheService.getCachedResult(any(), any())).thenReturn(Optional.empty());
    }

    private void stubHappyPathUpTo(GeminiResponse geminiResponse) {
        when(sessionRepository.findByUserIdAndSessionId(USER_ID, SESSION_ID)).thenReturn(Optional.of(session));
        when(messageRepository.findRecentBySessionId(anyLong(), any())).thenReturn(List.of());
        when(accountService.getMyAccounts(USER_ID)).thenReturn(List.of());
        when(investmentProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());
        when(geminiApiClient.generate(any())).thenReturn(geminiResponse);
    }

    @Nested
    @DisplayName("메시지 전송 - rate limit")
    class SendMessageRateLimit {

        @Test
        @DisplayName("분당/일일 한도를 초과하면 Gemini를 호출하지 않고 즉시 예외를 던진다")
        void fail_rateLimitExceeded() {
            when(rateLimiterService.isAllowed(USER_ID)).thenReturn(false);

            assertThatThrownBy(() -> aiPlanningService.sendMessage(USER_ID, SESSION_ID, new AiChatRequest("안녕")))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.GEMINI_RATE_LIMIT_EXCEEDED);

            verify(rateLimiterService, never()).increment(anyLong());
            verify(geminiApiClient, never()).generate(any());
        }

        @Test
        @DisplayName("한도를 통과하면 DART/네이버/Gemini 호출보다 먼저 increment로 카운터를 올린다")
        void success_incrementsBeforeGeminiCall() {
            when(rateLimiterService.isAllowed(USER_ID)).thenReturn(true);
            stubHappyPathUpTo(new GeminiResponse("답변", 5));
            // increment()가 Gemini 호출 이전에 이미 반영돼 있어야 한다
            // (RedisRateLimiterService 계약: isAllowed() 통과 "직후" increment).
            when(geminiApiClient.generate(any())).thenAnswer(invocation -> {
                verify(rateLimiterService).increment(USER_ID);
                return new GeminiResponse("답변", 5);
            });

            aiPlanningService.sendMessage(USER_ID, SESSION_ID, new AiChatRequest("삼성전자 어때?"));

            verify(rateLimiterService).increment(USER_ID);
        }
    }

    @Nested
    @DisplayName("메시지 전송 - Gemini 실패 시 orphan 메시지 방지")
    class SendMessageGeminiFailure {

        @Test
        @DisplayName("Gemini 호출이 실패하면 사용자 메시지도 저장되지 않는다")
        void fail_geminiFailure_doesNotSaveAnyMessage() {
            when(rateLimiterService.isAllowed(USER_ID)).thenReturn(true);
            when(sessionRepository.findByUserIdAndSessionId(USER_ID, SESSION_ID)).thenReturn(Optional.of(session));
            when(messageRepository.findRecentBySessionId(anyLong(), any())).thenReturn(List.of());
            when(accountService.getMyAccounts(USER_ID)).thenReturn(List.of());
            when(investmentProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());
            when(geminiApiClient.generate(any())).thenThrow(new CustomException(ErrorCode.EXTERNAL_API_ERROR));

            assertThatThrownBy(() -> aiPlanningService.sendMessage(USER_ID, SESSION_ID, new AiChatRequest("질문")))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.EXTERNAL_API_ERROR);

            verify(messageRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("대표 계좌/보유종목 선택")
    class FindPrimaryHolding {

        @Test
        @DisplayName("계좌가 없으면 보유종목 조회 자체를 하지 않는다")
        void noAccounts_skipsHoldingLookup() {
            when(rateLimiterService.isAllowed(USER_ID)).thenReturn(true);
            stubHappyPathUpTo(new GeminiResponse("답변", 5));

            aiPlanningService.sendMessage(USER_ID, SESSION_ID, new AiChatRequest("질문"));

            verify(holdingValuationService, never()).getHoldingValuations(anyLong());
        }

        @Test
        @DisplayName("계좌가 있으면 AccountService가 돌려준 계좌 목록의 첫 번째 계좌로 보유종목을 조회한다")
        void withAccounts_usesFirstAccountFromAccountService() {
            AccountInfoResponse accountInfo = new AccountInfoResponse(
                    ACCOUNT_ID, "계좌A", "ACC-0001", 1_000_000L, 0L, 1_000_000L, 0, AccountStatus.ACTIVE);

            when(rateLimiterService.isAllowed(USER_ID)).thenReturn(true);
            when(sessionRepository.findByUserIdAndSessionId(USER_ID, SESSION_ID)).thenReturn(Optional.of(session));
            when(messageRepository.findRecentBySessionId(anyLong(), any())).thenReturn(List.of());
            when(accountService.getMyAccounts(USER_ID)).thenReturn(List.of(accountInfo));
            when(holdingValuationService.getHoldingValuations(ACCOUNT_ID)).thenReturn(List.of());
            when(investmentProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());
            when(geminiApiClient.generate(any())).thenReturn(new GeminiResponse("답변", 5));

            aiPlanningService.sendMessage(USER_ID, SESSION_ID, new AiChatRequest("질문"));

            verify(holdingValuationService).getHoldingValuations(ACCOUNT_ID);
        }
    }

    @Nested
    @DisplayName("뉴스검색 도구 판단(함수 호출)")
    class ToolCallingJudgment {

        @Test
        @DisplayName("Gemini가 도구 호출이 필요없다고 판단하면(바로 텍스트 응답) 네이버를 호출하지 않고 Gemini도 1번만 부른다")
        void noFunctionCall_skipsToolAndCallsGeminiOnce() {
            when(rateLimiterService.isAllowed(USER_ID)).thenReturn(true);
            stubHappyPathUpTo(new GeminiResponse("어떤 SK 계열사를 말씀하시는지 알려주세요.", null));

            AiChatResponse response = aiPlanningService.sendMessage(USER_ID, SESSION_ID, new AiChatRequest("SK 어때?"));

            assertThat(response.content()).isEqualTo("안녕하세요! AI 재무설계사 STOCK입니다.\n\n어떤 SK 계열사를 말씀하시는지 알려주세요.");
            verify(geminiApiClient, times(1)).generate(any());
            verify(naverNewsApiClient, never()).search(any());
        }

        @Test
        @DisplayName("Gemini가 뉴스검색 도구 호출을 요청하면 네이버 뉴스 검색을 실행하고, 결과를 되돌려주는 2차 호출로 최종 답변을 받는다")
        void functionCall_executesToolAndCallsGeminiTwice() {
            GeminiResponse.FunctionCall functionCall =
                    new GeminiResponse.FunctionCall("search_securities_news", Map.of("companyName", "삼성전기", "topic", "실적 전망"));
            GeminiResponse firstResponse = new GeminiResponse(null, null, List.of(functionCall));
            GeminiResponse finalResponse = new GeminiResponse("삼성전기는 최근 실적이...", 12);

            when(rateLimiterService.isAllowed(USER_ID)).thenReturn(true);
            when(sessionRepository.findByUserIdAndSessionId(USER_ID, SESSION_ID)).thenReturn(Optional.of(session));
            when(messageRepository.findRecentBySessionId(anyLong(), any())).thenReturn(List.of());
            when(accountService.getMyAccounts(USER_ID)).thenReturn(List.of());
            when(investmentProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());
            when(geminiApiClient.generate(any())).thenReturn(firstResponse).thenReturn(finalResponse);
            when(naverNewsApiClient.search(new NaverNewsSearchRequest("삼성전기", "실적 전망", null)))
                    .thenReturn(new NaverNewsSearchResponse(List.of(
                            new NaverNewsResult("삼성전기 3분기 실적 호조", "영업이익이 전년 대비 크게 늘었다.",
                                    "https://www.hankyung.com/article/1", "Wed, 05 Aug 2026 09:00:00 +0900", "한국경제"))));

            AiChatResponse response = aiPlanningService.sendMessage(USER_ID, SESSION_ID, new AiChatRequest("삼성전기 사려는데 어때?"));

            assertThat(response.content()).isEqualTo("안녕하세요! AI 재무설계사 STOCK입니다.\n\n삼성전기는 최근 실적이...");
            verify(geminiApiClient, times(2)).generate(any());
            verify(naverNewsApiClient).search(new NaverNewsSearchRequest("삼성전기", "실적 전망", null));

            org.mockito.ArgumentCaptor<GeminiRequest> captor = org.mockito.ArgumentCaptor.forClass(GeminiRequest.class);
            verify(geminiApiClient, times(2)).generate(captor.capture());
            GeminiRequest secondRequest = captor.getAllValues().get(1);
            assertThat(secondRequest.functionExchangeRounds()).hasSize(1);
            assertThat(secondRequest.functionExchangeRounds().get(0)).hasSize(1);
            String toolResult = secondRequest.functionExchangeRounds().get(0).get(0).functionResult().get("result").toString();
            assertThat(toolResult).contains("삼성전기 3분기 실적 호조", "영업이익이 전년 대비 크게 늘었다.");
        }

        @Test
        @DisplayName("Gemini가 한 응답에서 뉴스검색+재무제표 도구를 동시에 요청하면 둘 다 함께 실행하고, Gemini는 딱 2번만 부른다")
        void parallelFunctionCalls_executesBothToolsInOneRound() {
            GeminiResponse.FunctionCall newsCall =
                    new GeminiResponse.FunctionCall("search_securities_news", Map.of("companyName", "삼성전자", "topic", "실적 뉴스"));
            GeminiResponse.FunctionCall financialsCall =
                    new GeminiResponse.FunctionCall("get_financial_statements", Map.of("companyName", "삼성전자", "period", "분기"));
            GeminiResponse parallelResponse = new GeminiResponse(null, null, List.of(newsCall, financialsCall));
            GeminiResponse finalResponse = new GeminiResponse("실적과 뉴스를 종합하면...", 20);

            when(rateLimiterService.isAllowed(USER_ID)).thenReturn(true);
            when(sessionRepository.findByUserIdAndSessionId(USER_ID, SESSION_ID)).thenReturn(Optional.of(session));
            when(messageRepository.findRecentBySessionId(anyLong(), any())).thenReturn(List.of());
            when(accountService.getMyAccounts(USER_ID)).thenReturn(List.of());
            when(investmentProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());
            when(geminiApiClient.generate(any())).thenReturn(parallelResponse).thenReturn(finalResponse);
            when(naverNewsApiClient.search(any())).thenReturn(new NaverNewsSearchResponse(List.of(
                    new NaverNewsResult("삼성전자 실적 뉴스", "요약 내용", "https://www.hankyung.com/article/2", "Wed, 05 Aug 2026 09:00:00 +0900", "한국경제"))));
            // period="분기"이므로 getRecentQuarterlyFinancials() 경로가 타야 한다 — corp_code를
            // 실제로 찾은 상태로 스텁해서 이 분기 분기(period branching) 로직 자체가 실행되도록 한다.
            // (예전 버전은 resolveCorpCodeByName을 Optional.empty()로 스텁해서 "찾지 못했다"로 조기
            // 반환돼 period 분기 로직이 아예 실행되지 않는 채로 테스트가 통과하고 있었다.)
            when(dartApiClient.resolveCorpCodeByName("삼성전자")).thenReturn(Optional.of("00126380"));
            when(dartApiClient.getRecentQuarterlyFinancials("00126380")).thenReturn(
                    new DartFinancialResponse("00126380", 2026, 333_605_938_000_000L, 43_601_051_000_000L,
                            45_206_805_000_000L, 566_942_110_000_000L, 130_621_773_000_000L, 436_320_337_000_000L));

            AiChatResponse response = aiPlanningService.sendMessage(USER_ID, SESSION_ID, new AiChatRequest("삼성전자 실적이랑 뉴스 같이 알려줘"));

            assertThat(response.content()).isEqualTo("안녕하세요! AI 재무설계사 STOCK입니다.\n\n실적과 뉴스를 종합하면...");
            // 도구 2개가 동시에 요청돼도 Gemini 호출은 "도구 1라운드 + 최종 답변" 2번으로 끝난다.
            verify(geminiApiClient, times(2)).generate(any());
            verify(naverNewsApiClient, times(1)).search(any());
            verify(dartApiClient, times(1)).resolveCorpCodeByName("삼성전자");
            // period="분기"이므로 연간(getFinancials)이 아니라 분기 조회가 실제로 호출돼야 한다.
            verify(dartApiClient, times(1)).getRecentQuarterlyFinancials("00126380");
            verify(dartApiClient, never()).getFinancials(any());

            org.mockito.ArgumentCaptor<GeminiRequest> captor = org.mockito.ArgumentCaptor.forClass(GeminiRequest.class);
            verify(geminiApiClient, times(2)).generate(captor.capture());
            GeminiRequest secondRequest = captor.getAllValues().get(1);
            // 두 도구 요청이 같은 라운드(모델 턴 1개)로 묶여서 재구성돼야 한다.
            assertThat(secondRequest.functionExchangeRounds()).hasSize(1);
            assertThat(secondRequest.functionExchangeRounds().get(0)).hasSize(2);
            // 재무제표 도구의 실행 결과에 실제 조회된 숫자가 반영됐는지도 확인한다.
            String financialsResult = secondRequest.functionExchangeRounds().get(0).stream()
                    .filter(exchange -> "get_financial_statements".equals(exchange.functionName()))
                    .findFirst()
                    .orElseThrow()
                    .functionResult()
                    .get("result")
                    .toString();
            assertThat(financialsResult).contains("333,605,938,000,000");
        }

        @Test
        @DisplayName("Gemini가 도구 호출을 요청해도 허용된 라운드(1번)를 넘기면 도구 없이 재호출해 텍스트 응답을 강제로 받는다")
        void repeatedFunctionCalls_stopsAtMaxRoundsAndForcesTextAnswer() {
            GeminiResponse.FunctionCall functionCall =
                    new GeminiResponse.FunctionCall("search_securities_news", Map.of("companyName", "삼성전자", "topic", "전망"));
            GeminiResponse functionCallResponse = new GeminiResponse(null, null, List.of(functionCall));
            GeminiResponse forcedFinalResponse = new GeminiResponse("검색 결과를 반영해 답변드립니다.", 5);

            when(rateLimiterService.isAllowed(USER_ID)).thenReturn(true);
            when(sessionRepository.findByUserIdAndSessionId(USER_ID, SESSION_ID)).thenReturn(Optional.of(session));
            when(messageRepository.findRecentBySessionId(anyLong(), any())).thenReturn(List.of());
            when(accountService.getMyAccounts(USER_ID)).thenReturn(List.of());
            when(investmentProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());
            // 무료 등급 Gemini API 일일 호출 한도가 낮아(2026-08-04) MAX_TOOL_CALL_ROUNDS를 1로
            // 낮췄다 — 도구 요청이 와도 1라운드만 실행하고, tools 없이 보낸 마지막 호출에서
            // 텍스트를 강제로 받는다.
            when(geminiApiClient.generate(any()))
                    .thenReturn(functionCallResponse, forcedFinalResponse);
            when(naverNewsApiClient.search(any())).thenReturn(new NaverNewsSearchResponse(List.of(
                    new NaverNewsResult("삼성전자 전망 관련 기사", "요약 내용", "https://www.hankyung.com/article/3", "Wed, 05 Aug 2026 09:00:00 +0900", "한국경제"))));

            AiChatResponse response = aiPlanningService.sendMessage(USER_ID, SESSION_ID, new AiChatRequest("삼성전자 계속 물어볼게"));

            assertThat(response.content()).isEqualTo("안녕하세요! AI 재무설계사 STOCK입니다.\n\n검색 결과를 반영해 답변드립니다.");
            // MAX_TOOL_CALL_ROUNDS(1)만큼 도구 사용 라운드 + tools 없이 강제한 마지막 1회 = 총 2번.
            verify(geminiApiClient, times(2)).generate(any());
            verify(naverNewsApiClient, times(1)).search(any());

            org.mockito.ArgumentCaptor<GeminiRequest> captor = org.mockito.ArgumentCaptor.forClass(GeminiRequest.class);
            verify(geminiApiClient, times(2)).generate(captor.capture());
            GeminiRequest lastRequest = captor.getAllValues().get(1);
            assertThat(lastRequest.tools()).isEmpty();
            assertThat(lastRequest.functionExchangeRounds()).hasSize(1);
        }

        @Test
        @DisplayName("네이버 뉴스 검색이 실패해도 예외 없이 실패 사실을 Gemini에 전달해 최종 답변을 받는다")
        void toolExecutionFails_stillReturnsFinalAnswer() {
            GeminiResponse.FunctionCall functionCall =
                    new GeminiResponse.FunctionCall("search_securities_news", Map.of("companyName", "삼성전자", "topic", "전망"));
            GeminiResponse firstResponse = new GeminiResponse(null, null, List.of(functionCall));
            GeminiResponse finalResponse = new GeminiResponse("지금은 관련 뉴스를 확인할 수 없지만...", 8);

            when(rateLimiterService.isAllowed(USER_ID)).thenReturn(true);
            when(sessionRepository.findByUserIdAndSessionId(USER_ID, SESSION_ID)).thenReturn(Optional.of(session));
            when(messageRepository.findRecentBySessionId(anyLong(), any())).thenReturn(List.of());
            when(accountService.getMyAccounts(USER_ID)).thenReturn(List.of());
            when(investmentProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());
            when(geminiApiClient.generate(any())).thenReturn(firstResponse).thenReturn(finalResponse);
            when(naverNewsApiClient.search(any())).thenThrow(new CustomException(ErrorCode.EXTERNAL_API_ERROR));

            AiChatResponse response = aiPlanningService.sendMessage(USER_ID, SESSION_ID, new AiChatRequest("삼성전자 어때?"));

            assertThat(response.content()).isEqualTo("안녕하세요! AI 재무설계사 STOCK입니다.\n\n지금은 관련 뉴스를 확인할 수 없지만...");
            verify(geminiApiClient, times(2)).generate(any());
        }

        @Test
        @DisplayName("주제까지 정확히 맞는 뉴스가 없으면, 억지로 다른 소식을 끌어다 붙이지 않고 정직하게 못 찾았다고 답한다")
        void functionCall_newsSearch_noMatch_reportsNotFoundWithoutFabricatingRelevance() {
            GeminiResponse.FunctionCall functionCall = new GeminiResponse.FunctionCall(
                    "search_securities_news", Map.of("companyName", "삼성전자", "topic", "실적"));
            GeminiResponse firstResponse = new GeminiResponse(null, null, List.of(functionCall));
            GeminiResponse finalResponse = new GeminiResponse("실적 관련 소식은 찾지 못했습니다...", 10);

            when(rateLimiterService.isAllowed(USER_ID)).thenReturn(true);
            stubHappyPathUpTo(firstResponse);
            when(geminiApiClient.generate(any())).thenReturn(firstResponse).thenReturn(finalResponse);
            // NaverNewsApiClient는 주제까지 맞는 근거가 없으면 results=빈 리스트를 준다
            // (회사명만 맞는 무관한 기사를 "참고용"으로 억지로 끼워주지 않는다, 2026-08-05 확인).
            when(naverNewsApiClient.search(new NaverNewsSearchRequest("삼성전자", "실적", null)))
                    .thenReturn(new NaverNewsSearchResponse(List.of()));

            AiChatResponse response = aiPlanningService.sendMessage(USER_ID, SESSION_ID, new AiChatRequest("삼성전자 실적 어때?"));

            assertThat(response.content()).isEqualTo("안녕하세요! AI 재무설계사 STOCK입니다.\n\n실적 관련 소식은 찾지 못했습니다...");
            org.mockito.ArgumentCaptor<GeminiRequest> captor = org.mockito.ArgumentCaptor.forClass(GeminiRequest.class);
            verify(geminiApiClient, times(2)).generate(captor.capture());
            String toolResult = captor.getAllValues().get(1).functionExchangeRounds().get(0).get(0)
                    .functionResult().get("result").toString();
            assertThat(toolResult).contains("찾지 못했습니다");
        }

        @Test
        @DisplayName("Gemini가 companyName 없이(스키마 위반) 뉴스검색을 요청해도 네이버를 호출하지 않고 안전하게 처리한다")
        void functionCall_newsSearch_missingCompanyName_skipsNaverCall() {
            GeminiResponse.FunctionCall functionCall = new GeminiResponse.FunctionCall(
                    "search_securities_news", Map.of("topic", "실적"));
            GeminiResponse firstResponse = new GeminiResponse(null, null, List.of(functionCall));
            GeminiResponse finalResponse = new GeminiResponse("어떤 종목인지 먼저 알려주세요...", 5);

            when(rateLimiterService.isAllowed(USER_ID)).thenReturn(true);
            stubHappyPathUpTo(firstResponse);
            when(geminiApiClient.generate(any())).thenReturn(firstResponse).thenReturn(finalResponse);

            AiChatResponse response = aiPlanningService.sendMessage(USER_ID, SESSION_ID, new AiChatRequest("실적 어때?"));

            assertThat(response.content()).isEqualTo("안녕하세요! AI 재무설계사 STOCK입니다.\n\n어떤 종목인지 먼저 알려주세요...");
            verify(naverNewsApiClient, never()).search(any());
        }

        @Test
        @DisplayName("Gemini가 자본변동 도구 호출을 요청하면 DART를 조회해 실행 결과를 최종 답변에 반영한다")
        void functionCall_capitalChange_executesToolAndReturnsResult() {
            GeminiResponse.FunctionCall functionCall = new GeminiResponse.FunctionCall(
                    "get_capital_change_info", Map.of("companyName", "삼성전자", "changeType", "유상증자"));
            GeminiResponse firstResponse = new GeminiResponse(null, null, List.of(functionCall));
            GeminiResponse finalResponse = new GeminiResponse("유상증자 내역을 정리하면...", 10);

            when(rateLimiterService.isAllowed(USER_ID)).thenReturn(true);
            stubHappyPathUpTo(firstResponse);
            when(geminiApiClient.generate(any())).thenReturn(firstResponse).thenReturn(finalResponse);
            when(dartApiClient.resolveCorpCodeByName("삼성전자")).thenReturn(Optional.of("00126380"));
            when(dartApiClient.getCapitalChangeDecisions("00126380", "유상증자")).thenReturn(List.of(
                    Map.of("bddd", "20260101", "nstk_ostk_cnt", "1000000",
                            "fdpp_fclt", "0", "fdpp_op", "0", "fdpp_dtrp", "0", "ic_mthn", "제3자배정증자")));

            AiChatResponse response = aiPlanningService.sendMessage(USER_ID, SESSION_ID, new AiChatRequest("삼성전자 유상증자 했어?"));

            assertThat(response.content()).isEqualTo("안녕하세요! AI 재무설계사 STOCK입니다.\n\n유상증자 내역을 정리하면...");
            verify(dartApiClient).getCapitalChangeDecisions("00126380", "유상증자");

            org.mockito.ArgumentCaptor<GeminiRequest> captor = org.mockito.ArgumentCaptor.forClass(GeminiRequest.class);
            verify(geminiApiClient, times(2)).generate(captor.capture());
            String toolResult = captor.getAllValues().get(1).functionExchangeRounds().get(0).get(0)
                    .functionResult().get("result").toString();
            assertThat(toolResult).contains("제3자배정증자");
        }

        @Test
        @DisplayName("자본변동 도구 호출에 changeType이 없으면(스키마 위반) 유상증자를 기본값으로 조회한다")
        void functionCall_capitalChange_missingChangeType_defaultsToPaidIncrease() {
            GeminiResponse.FunctionCall functionCall = new GeminiResponse.FunctionCall(
                    "get_capital_change_info", Map.of("companyName", "삼성전자"));
            GeminiResponse firstResponse = new GeminiResponse(null, null, List.of(functionCall));
            GeminiResponse finalResponse = new GeminiResponse("유상증자 내역을 정리하면...", 10);

            when(rateLimiterService.isAllowed(USER_ID)).thenReturn(true);
            stubHappyPathUpTo(firstResponse);
            when(geminiApiClient.generate(any())).thenReturn(firstResponse).thenReturn(finalResponse);
            when(dartApiClient.resolveCorpCodeByName("삼성전자")).thenReturn(Optional.of("00126380"));
            when(dartApiClient.getCapitalChangeDecisions("00126380", "유상증자")).thenReturn(List.of());

            aiPlanningService.sendMessage(USER_ID, SESSION_ID, new AiChatRequest("삼성전자 증자 소식 있어?"));

            // CAPITAL_CHANGE_TOOL의 changeType description은 "애매하면 유상증자가 기본"이라고
            // Gemini에 지시하므로, changeType이 아예 없을 때도 무상증자가 아니라 유상증자로
            // 조회해야 한다(과거 !"유상증자".equals(changeType) 방식은 반대로 무상증자를 기본값으로
            // 삼아 이 지시와 모순됐었다).
            verify(dartApiClient).getCapitalChangeDecisions("00126380", "유상증자");
            verify(dartApiClient, never()).getCapitalChangeDecisions("00126380", "무상증자");
        }

        @Test
        @DisplayName("Gemini가 companyName 없이(스키마 위반) DART 조회 도구를 요청해도 'null'을 노출하지 않고 안전하게 처리한다")
        void functionCall_financials_missingCompanyName_skipsDartCall() {
            GeminiResponse.FunctionCall functionCall = new GeminiResponse.FunctionCall(
                    "get_financial_statements", Map.of("period", "분기"));
            GeminiResponse firstResponse = new GeminiResponse(null, null, List.of(functionCall));
            GeminiResponse finalResponse = new GeminiResponse("어떤 종목인지 먼저 알려주세요...", 5);

            when(rateLimiterService.isAllowed(USER_ID)).thenReturn(true);
            stubHappyPathUpTo(firstResponse);
            when(geminiApiClient.generate(any())).thenReturn(firstResponse).thenReturn(finalResponse);

            aiPlanningService.sendMessage(USER_ID, SESSION_ID, new AiChatRequest("실적 어때?"));

            verify(dartApiClient, never()).resolveCorpCodeByName(any());
            org.mockito.ArgumentCaptor<GeminiRequest> captor = org.mockito.ArgumentCaptor.forClass(GeminiRequest.class);
            verify(geminiApiClient, times(2)).generate(captor.capture());
            String toolResult = captor.getAllValues().get(1).functionExchangeRounds().get(0).get(0)
                    .functionResult().get("result").toString();
            assertThat(toolResult).doesNotContain("null");
        }

        @Test
        @DisplayName("Gemini가 소유권 도구 호출을 요청하면 DART를 조회해 실행 결과를 최종 답변에 반영한다")
        void functionCall_ownership_executesToolAndReturnsResult() {
            GeminiResponse.FunctionCall functionCall = new GeminiResponse.FunctionCall(
                    "get_ownership_info", Map.of("companyName", "삼성전자", "infoType", "현황"));
            GeminiResponse firstResponse = new GeminiResponse(null, null, List.of(functionCall));
            GeminiResponse finalResponse = new GeminiResponse("최대주주 현황을 알려드리면...", 10);

            when(rateLimiterService.isAllowed(USER_ID)).thenReturn(true);
            stubHappyPathUpTo(firstResponse);
            when(geminiApiClient.generate(any())).thenReturn(firstResponse).thenReturn(finalResponse);
            when(dartApiClient.resolveCorpCodeByName("삼성전자")).thenReturn(Optional.of("00126380"));
            when(dartApiClient.getOwnershipInfo("00126380", "현황")).thenReturn(List.of(
                    Map.of("nm", "이재용", "relate", "본인",
                            "bsis_posesn_stock_qota_rt", "1.63", "trmend_posesn_stock_qota_rt", "1.63", "stlm_dt", "2026-06-30")));

            AiChatResponse response = aiPlanningService.sendMessage(USER_ID, SESSION_ID, new AiChatRequest("삼성전자 최대주주 누구야?"));

            assertThat(response.content()).isEqualTo("안녕하세요! AI 재무설계사 STOCK입니다.\n\n최대주주 현황을 알려드리면...");
            org.mockito.ArgumentCaptor<GeminiRequest> captor = org.mockito.ArgumentCaptor.forClass(GeminiRequest.class);
            verify(geminiApiClient, times(2)).generate(captor.capture());
            String toolResult = captor.getAllValues().get(1).functionExchangeRounds().get(0).get(0)
                    .functionResult().get("result").toString();
            assertThat(toolResult).contains("이재용");
        }

        @Test
        @DisplayName("소유권 조회 결과 행이 식별용 필드만 채워져 있으면(비상장 등) 이유를 담아 답한다")
        void functionCall_ownership_allBlankRows_explainsReason() {
            GeminiResponse.FunctionCall functionCall = new GeminiResponse.FunctionCall(
                    "get_ownership_info", Map.of("companyName", "SK쉴더스", "infoType", "현황"));
            GeminiResponse firstResponse = new GeminiResponse(null, null, List.of(functionCall));
            GeminiResponse finalResponse = new GeminiResponse("최대주주 정보는 확인이 어렵습니다...", 10);

            when(rateLimiterService.isAllowed(USER_ID)).thenReturn(true);
            stubHappyPathUpTo(firstResponse);
            when(geminiApiClient.generate(any())).thenReturn(firstResponse).thenReturn(finalResponse);
            when(dartApiClient.resolveCorpCodeByName("SK쉴더스")).thenReturn(Optional.of("00999999"));
            when(dartApiClient.getOwnershipInfo("00999999", "현황")).thenReturn(List.of(
                    Map.of("rcept_no", "20260101000001", "corp_code", "00999999", "corp_name", "SK쉴더스", "stlm_dt", "2026-06-30")));
            // isBlankOfContent()는 DART 응답 형태를 아는 DartApiClient로 옮겨졌다(2026-08-06
            // 코드리뷰 후속 - AiPlanningService.allItemsBlank()와의 중복 제거) — 여기서는
            // dartApiClient가 mock이라 실제 판정 로직 대신 "식별용 필드만 채워진 행"이라는
            // 이 테스트 시나리오를 그대로 스텁으로 재현한다.
            when(dartApiClient.isBlankOfContent(any(), eq(DartApiClient.OWNERSHIP_BOILERPLATE_KEYS))).thenReturn(true);

            aiPlanningService.sendMessage(USER_ID, SESSION_ID, new AiChatRequest("SK쉴더스 최대주주 누구야?"));

            org.mockito.ArgumentCaptor<GeminiRequest> captor = org.mockito.ArgumentCaptor.forClass(GeminiRequest.class);
            verify(geminiApiClient, times(2)).generate(captor.capture());
            String toolResult = captor.getAllValues().get(1).functionExchangeRounds().get(0).get(0)
                    .functionResult().get("result").toString();
            assertThat(toolResult).contains("비상장");
        }

        @Test
        @DisplayName("실제 라이브 테스트로 확인된 사례 - 상장사라도 '변동' 조회가 빈 값뿐이면 '비상장'이 아니라 '변동 없음'으로 설명한다")
        void functionCall_ownershipChange_allBlankRows_explainsNoChangeNotUnlisted() {
            GeminiResponse.FunctionCall functionCall = new GeminiResponse.FunctionCall(
                    "get_ownership_info", Map.of("companyName", "삼성전자", "infoType", "변동"));
            GeminiResponse firstResponse = new GeminiResponse(null, null, List.of(functionCall));
            GeminiResponse finalResponse = new GeminiResponse("최대주주는 최근 안 바뀌었어요...", 10);

            when(rateLimiterService.isAllowed(USER_ID)).thenReturn(true);
            stubHappyPathUpTo(firstResponse);
            when(geminiApiClient.generate(any())).thenReturn(firstResponse).thenReturn(finalResponse);
            when(dartApiClient.resolveCorpCodeByName("삼성전자")).thenReturn(Optional.of("00126380"));
            // 실제 DART 응답(2026-08-05, 삼성전자 최대주주 변동현황) — 상장사인데도 행은 오고
            // change_on 등 실질 필드가 전부 "-"뿐이었다(그 기간 최대주주 변동이 없었다는 뜻).
            when(dartApiClient.getOwnershipInfo("00126380", "변동")).thenReturn(List.of(
                    Map.of("rcept_no", "20260515002181", "corp_code", "00126380", "corp_name", "삼성전자",
                            "stlm_dt", "2026-03-31", "change_on", "-", "mxmm_shrholdr_nm", "-",
                            "posesn_stock_co", "-", "qota_rt", "-", "change_cause", "-", "rm", "-")));
            // isBlankOfContent()는 DartApiClient로 옮겨졌다(위 SK쉴더스 테스트 주석 참고) —
            // dartApiClient가 mock이라 "행은 왔지만 실질 값이 전부 '-'" 시나리오를 스텁으로 재현한다.
            when(dartApiClient.isBlankOfContent(any(), eq(DartApiClient.OWNERSHIP_BOILERPLATE_KEYS))).thenReturn(true);

            aiPlanningService.sendMessage(USER_ID, SESSION_ID, new AiChatRequest("삼성전자 최대주주 바뀐 적 있어?"));

            org.mockito.ArgumentCaptor<GeminiRequest> captor = org.mockito.ArgumentCaptor.forClass(GeminiRequest.class);
            verify(geminiApiClient, times(2)).generate(captor.capture());
            String toolResult = captor.getAllValues().get(1).functionExchangeRounds().get(0).get(0)
                    .functionResult().get("result").toString();
            assertThat(toolResult).contains("바뀌지 않았다");
            assertThat(toolResult).doesNotContain("비상장");
        }

        @Test
        @DisplayName("Gemini가 기타공시 도구 호출을 요청하면 DART를 조회해 실행 결과를 최종 답변에 반영한다")
        void functionCall_disclosure_executesToolAndReturnsResult() {
            GeminiResponse.FunctionCall functionCall = new GeminiResponse.FunctionCall(
                    "get_disclosure_info", Map.of("companyName", "삼성전자", "disclosureType", "소송제기"));
            GeminiResponse firstResponse = new GeminiResponse(null, null, List.of(functionCall));
            GeminiResponse finalResponse = new GeminiResponse("소송 관련 공시를 확인해보면...", 10);

            when(rateLimiterService.isAllowed(USER_ID)).thenReturn(true);
            stubHappyPathUpTo(firstResponse);
            when(geminiApiClient.generate(any())).thenReturn(firstResponse).thenReturn(finalResponse);
            when(dartApiClient.resolveCorpCodeByName("삼성전자")).thenReturn(Optional.of("00126380"));
            when(dartApiClient.getDisclosureInfo("00126380", "소송제기")).thenReturn(List.of(
                    Map.of("bddd", "20260101", "ls_pp", "손해배상청구소송")));

            AiChatResponse response = aiPlanningService.sendMessage(USER_ID, SESSION_ID, new AiChatRequest("삼성전자 소송당한 적 있어?"));

            assertThat(response.content()).isEqualTo("안녕하세요! AI 재무설계사 STOCK입니다.\n\n소송 관련 공시를 확인해보면...");
            verify(dartApiClient).getDisclosureInfo("00126380", "소송제기");

            org.mockito.ArgumentCaptor<GeminiRequest> captor = org.mockito.ArgumentCaptor.forClass(GeminiRequest.class);
            verify(geminiApiClient, times(2)).generate(captor.capture());
            String toolResult = captor.getAllValues().get(1).functionExchangeRounds().get(0).get(0)
                    .functionResult().get("result").toString();
            assertThat(toolResult).contains("손해배상청구소송");
        }
    }

    @Nested
    @DisplayName("도구 실행 결과 세션 캐싱 (RedisAiToolCacheService)")
    class ToolResultCaching {

        @Test
        @DisplayName("같은 세션에서 캐시된 결과가 있으면 DART를 다시 호출하지 않고 캐시값을 그대로 쓴다")
        void cacheHit_skipsDartCallAndReusesCachedResult() {
            GeminiResponse.FunctionCall functionCall = new GeminiResponse.FunctionCall(
                    "get_financial_statements", Map.of("companyName", "삼성전자", "period", "분기"));
            GeminiResponse firstResponse = new GeminiResponse(null, null, List.of(functionCall));
            GeminiResponse finalResponse = new GeminiResponse("아까 본 실적 기준으로 보면...", 10);

            when(rateLimiterService.isAllowed(USER_ID)).thenReturn(true);
            when(sessionRepository.findByUserIdAndSessionId(USER_ID, SESSION_ID)).thenReturn(Optional.of(session));
            when(messageRepository.findRecentBySessionId(anyLong(), any())).thenReturn(List.of());
            when(accountService.getMyAccounts(USER_ID)).thenReturn(List.of());
            when(investmentProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());
            when(geminiApiClient.generate(any())).thenReturn(firstResponse).thenReturn(finalResponse);
            when(aiToolCacheService.getCachedResult(eq(SESSION_ID), any()))
                    .thenReturn(Optional.of("[캐시됨] 2026년 매출액 333605938000000원"));

            AiChatResponse response = aiPlanningService.sendMessage(USER_ID, SESSION_ID, new AiChatRequest("그럼 순이익은요?"));

            assertThat(response.content()).isEqualTo("안녕하세요! AI 재무설계사 STOCK입니다.\n\n아까 본 실적 기준으로 보면...");
            verify(dartApiClient, never()).resolveCorpCodeByName(any());
            verify(dartApiClient, never()).getRecentQuarterlyFinancials(any());
            verify(aiToolCacheService, never()).cacheResult(any(), any(), any());

            org.mockito.ArgumentCaptor<GeminiRequest> captor = org.mockito.ArgumentCaptor.forClass(GeminiRequest.class);
            verify(geminiApiClient, times(2)).generate(captor.capture());
            String toolResult = captor.getAllValues().get(1).functionExchangeRounds().get(0).get(0)
                    .functionResult().get("result").toString();
            assertThat(toolResult).isEqualTo("[캐시됨] 2026년 매출액 333605938000000원");
        }

        @Test
        @DisplayName("캐시가 없으면 실제로 조회한 뒤 다음 재사용을 위해 결과를 캐싱한다")
        void cacheMiss_fetchesThenCachesResult() {
            GeminiResponse.FunctionCall functionCall = new GeminiResponse.FunctionCall(
                    "get_financial_statements", Map.of("companyName", "삼성전자", "period", "분기"));
            GeminiResponse firstResponse = new GeminiResponse(null, null, List.of(functionCall));
            GeminiResponse finalResponse = new GeminiResponse("실적을 정리하면...", 10);

            when(rateLimiterService.isAllowed(USER_ID)).thenReturn(true);
            when(sessionRepository.findByUserIdAndSessionId(USER_ID, SESSION_ID)).thenReturn(Optional.of(session));
            when(messageRepository.findRecentBySessionId(anyLong(), any())).thenReturn(List.of());
            when(accountService.getMyAccounts(USER_ID)).thenReturn(List.of());
            when(investmentProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());
            when(geminiApiClient.generate(any())).thenReturn(firstResponse).thenReturn(finalResponse);
            when(dartApiClient.resolveCorpCodeByName("삼성전자")).thenReturn(Optional.of("00126380"));
            when(dartApiClient.getRecentQuarterlyFinancials("00126380")).thenReturn(
                    new DartFinancialResponse("00126380", 2026, 333_605_938_000_000L, 43_601_051_000_000L,
                            45_206_805_000_000L, 566_942_110_000_000L, 130_621_773_000_000L, 436_320_337_000_000L));

            aiPlanningService.sendMessage(USER_ID, SESSION_ID, new AiChatRequest("삼성전자 실적 어때?"));

            org.mockito.ArgumentCaptor<String> keyCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
            org.mockito.ArgumentCaptor<String> valueCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(aiToolCacheService).cacheResult(eq(SESSION_ID), keyCaptor.capture(), valueCaptor.capture());
            assertThat(keyCaptor.getValue()).startsWith("get_financial_statements?");
            assertThat(valueCaptor.getValue()).contains("333,605,938,000,000");
        }
    }

    @Nested
    @DisplayName("LS 도구 3개(외국인/기관동향·투자의견·주주총회일정, 2026-08-10 추가)")
    class LsInvestInfoTools {

        @Test
        @DisplayName("외국인/기관 매매동향 도구 호출 시 stockCode로 조회해 최종 답변에 반영한다")
        void functionCall_foreignInstitutionalTrend_executesToolAndReturnsResult() {
            GeminiResponse.FunctionCall functionCall = new GeminiResponse.FunctionCall(
                    "get_foreign_institutional_trend", Map.of("companyName", "삼성전자"));
            GeminiResponse firstResponse = new GeminiResponse(null, null, List.of(functionCall));
            GeminiResponse finalResponse = new GeminiResponse("최근 외국인이 순매수 중입니다...", 10);

            when(rateLimiterService.isAllowed(USER_ID)).thenReturn(true);
            stubHappyPathUpTo(firstResponse);
            when(geminiApiClient.generate(any())).thenReturn(firstResponse).thenReturn(finalResponse);
            when(dartApiClient.resolveStockCodeByName("삼성전자")).thenReturn(Optional.of("005930"));
            when(lsInvestorTrendApiClient.getTrend("005930", null)).thenReturn(List.of(
                    LsForeignInstitutionalTrendDto.builder()
                            .date("20260810").closePrice(71000L)
                            .foreignNetBuyKrx(700L).institutionNetBuyKrx(300L).individualNetBuyKrx(-1000L)
                            .programTradingVolume(5000L).foreignExhaustionRate(51.23)
                            .shortSellingVolume(200L).shortSellingValue(14200000L)
                            .build()));

            AiChatResponse response = aiPlanningService.sendMessage(USER_ID, SESSION_ID, new AiChatRequest("삼성전자 외국인 사고 있어?"));

            assertThat(response.content()).isEqualTo("안녕하세요! AI 재무설계사 STOCK입니다.\n\n최근 외국인이 순매수 중입니다...");
            verify(lsInvestorTrendApiClient).getTrend("005930", null);

            org.mockito.ArgumentCaptor<GeminiRequest> captor = org.mockito.ArgumentCaptor.forClass(GeminiRequest.class);
            verify(geminiApiClient, times(2)).generate(captor.capture());
            String toolResult = captor.getAllValues().get(1).functionExchangeRounds().get(0).get(0)
                    .functionResult().get("result").toString();
            assertThat(toolResult).contains("외국인 순매수 +700주");
        }

        @Test
        @DisplayName("투자의견 도구 호출 시 stockCode로 조회해 최종 답변에 반영한다")
        void functionCall_investmentOpinion_executesToolAndReturnsResult() {
            GeminiResponse.FunctionCall functionCall = new GeminiResponse.FunctionCall(
                    "get_investment_opinion", Map.of("companyName", "삼성전자"));
            GeminiResponse firstResponse = new GeminiResponse(null, null, List.of(functionCall));
            GeminiResponse finalResponse = new GeminiResponse("목표주가를 상향 조정했습니다...", 10);

            when(rateLimiterService.isAllowed(USER_ID)).thenReturn(true);
            stubHappyPathUpTo(firstResponse);
            when(geminiApiClient.generate(any())).thenReturn(firstResponse).thenReturn(finalResponse);
            when(dartApiClient.resolveStockCodeByName("삼성전자")).thenReturn(Optional.of("005930"));
            when(lsInvestInfoApiClient.getInvestmentOpinions("005930")).thenReturn(List.of(
                    LsInvestmentOpinionDto.builder()
                            .date("20260805").securitiesFirm("메리츠")
                            .opinionBefore("HOLD").opinionAfter("BUY")
                            .targetPriceBefore(24000L).targetPriceAfter(30000L).closePriceOnDate(28500L)
                            .build()));

            AiChatResponse response = aiPlanningService.sendMessage(USER_ID, SESSION_ID, new AiChatRequest("삼성전자 목표주가 얼마로 올렸대?"));

            assertThat(response.content()).isEqualTo("안녕하세요! AI 재무설계사 STOCK입니다.\n\n목표주가를 상향 조정했습니다...");
            verify(lsInvestInfoApiClient).getInvestmentOpinions("005930");

            org.mockito.ArgumentCaptor<GeminiRequest> captor = org.mockito.ArgumentCaptor.forClass(GeminiRequest.class);
            verify(geminiApiClient, times(2)).generate(captor.capture());
            String toolResult = captor.getAllValues().get(1).functionExchangeRounds().get(0).get(0)
                    .functionResult().get("result").toString();
            assertThat(toolResult).contains("메리츠").contains("30,000원");
        }

        @Test
        @DisplayName("주주총회 일정 도구 호출 시 stockCode로 조회해 최종 답변에 반영한다")
        void functionCall_shareholderMeeting_executesToolAndReturnsResult() {
            GeminiResponse.FunctionCall functionCall = new GeminiResponse.FunctionCall(
                    "get_shareholder_meeting_schedule", Map.of("companyName", "삼성전자"));
            GeminiResponse firstResponse = new GeminiResponse(null, null, List.of(functionCall));
            GeminiResponse finalResponse = new GeminiResponse("올해 주주총회는 3월입니다...", 10);

            when(rateLimiterService.isAllowed(USER_ID)).thenReturn(true);
            stubHappyPathUpTo(firstResponse);
            when(geminiApiClient.generate(any())).thenReturn(firstResponse).thenReturn(finalResponse);
            when(dartApiClient.resolveStockCodeByName("삼성전자")).thenReturn(Optional.of("005930"));
            when(lsInvestInfoApiClient.getShareholderMeetingSchedule("005930")).thenReturn(List.of(
                    LsShareholderMeetingDto.builder().date("20260315").eventName("주주총회").build()));

            AiChatResponse response = aiPlanningService.sendMessage(USER_ID, SESSION_ID, new AiChatRequest("삼성전자 주주총회 언제야?"));

            assertThat(response.content()).isEqualTo("안녕하세요! AI 재무설계사 STOCK입니다.\n\n올해 주주총회는 3월입니다...");
            verify(lsInvestInfoApiClient).getShareholderMeetingSchedule("005930");

            org.mockito.ArgumentCaptor<GeminiRequest> captor = org.mockito.ArgumentCaptor.forClass(GeminiRequest.class);
            verify(geminiApiClient, times(2)).generate(captor.capture());
            String toolResult = captor.getAllValues().get(1).functionExchangeRounds().get(0).get(0)
                    .functionResult().get("result").toString();
            assertThat(toolResult).contains("20260315").contains("주주총회");
        }

        @Test
        @DisplayName("Gemini가 companyName 없이(스키마 위반) LS 도구를 요청해도 안전하게 처리하고 LS를 호출하지 않는다")
        void functionCall_lsTools_missingCompanyName_skipsLsCall() {
            GeminiResponse.FunctionCall functionCall = new GeminiResponse.FunctionCall(
                    "get_foreign_institutional_trend", Map.of());
            GeminiResponse firstResponse = new GeminiResponse(null, null, List.of(functionCall));
            GeminiResponse finalResponse = new GeminiResponse("어떤 종목인지 먼저 알려주세요...", 5);

            when(rateLimiterService.isAllowed(USER_ID)).thenReturn(true);
            stubHappyPathUpTo(firstResponse);
            when(geminiApiClient.generate(any())).thenReturn(firstResponse).thenReturn(finalResponse);

            aiPlanningService.sendMessage(USER_ID, SESSION_ID, new AiChatRequest("외국인 사고 있어?"));

            verify(lsInvestorTrendApiClient, never()).getTrend(any(), any());
        }

        @Test
        @DisplayName("종목코드를 찾지 못하면 LS를 호출하지 않고 확인 불가 문구로 답한다")
        void functionCall_lsTools_unresolvedStockCode_skipsLsCall() {
            GeminiResponse.FunctionCall functionCall = new GeminiResponse.FunctionCall(
                    "get_investment_opinion", Map.of("companyName", "존재안함"));
            GeminiResponse firstResponse = new GeminiResponse(null, null, List.of(functionCall));
            GeminiResponse finalResponse = new GeminiResponse("확인이 어렵습니다...", 5);

            when(rateLimiterService.isAllowed(USER_ID)).thenReturn(true);
            stubHappyPathUpTo(firstResponse);
            when(geminiApiClient.generate(any())).thenReturn(firstResponse).thenReturn(finalResponse);
            when(dartApiClient.resolveStockCodeByName("존재안함")).thenReturn(Optional.empty());

            aiPlanningService.sendMessage(USER_ID, SESSION_ID, new AiChatRequest("존재안함 목표주가는?"));

            verify(lsInvestInfoApiClient, never()).getInvestmentOpinions(any());
        }
    }

    @Nested
    @DisplayName("세션 소유권 검증")
    class SessionOwnership {

        @Test
        @DisplayName("내 세션이 아니면 AI_SESSION_NOT_FOUND 예외를 던지고 Gemini를 호출하지 않는다")
        void fail_notMySession() {
            when(rateLimiterService.isAllowed(USER_ID)).thenReturn(true);
            when(sessionRepository.findByUserIdAndSessionId(USER_ID, SESSION_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> aiPlanningService.sendMessage(USER_ID, SESSION_ID, new AiChatRequest("질문")))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.AI_SESSION_NOT_FOUND);

            verify(geminiApiClient, never()).generate(any());
        }
    }

    @Nested
    @DisplayName("세션 제목/활동시각 갱신")
    class SessionMetadata {

        @Test
        @DisplayName("첫 메시지에서만 세션 제목을 채우고, 이후 메시지에서는 덮어쓰지 않는다")
        void savesTitleOnlyOnce() {
            when(rateLimiterService.isAllowed(USER_ID)).thenReturn(true);
            stubHappyPathUpTo(new GeminiResponse("답변", 5));

            aiPlanningService.sendMessage(USER_ID, SESSION_ID, new AiChatRequest("첫 질문입니다"));
            assertThat(session.getTitle()).isEqualTo("첫 질문입니다");

            aiPlanningService.sendMessage(USER_ID, SESSION_ID, new AiChatRequest("두 번째 질문"));
            assertThat(session.getTitle()).isEqualTo("첫 질문입니다");
        }

        @Test
        @DisplayName("메시지를 주고받을 때마다 세션의 updatedAt이 갱신된다")
        void recordsActivityOnEveryTurn() {
            when(rateLimiterService.isAllowed(USER_ID)).thenReturn(true);
            stubHappyPathUpTo(new GeminiResponse("답변", 5));

            aiPlanningService.sendMessage(USER_ID, SESSION_ID, new AiChatRequest("질문"));
            var firstUpdatedAt = session.getUpdatedAt();

            aiPlanningService.sendMessage(USER_ID, SESSION_ID, new AiChatRequest("두 번째 질문"));

            assertThat(session.getUpdatedAt()).isAfterOrEqualTo(firstUpdatedAt);
        }
    }

}
