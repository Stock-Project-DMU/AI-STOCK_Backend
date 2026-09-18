package com.teamfp.aistock.manualcheck;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import com.teamfp.aistock.domain.account.service.AccountService;
import com.teamfp.aistock.domain.ai.dto.request.AiChatRequest;
import com.teamfp.aistock.domain.ai.dto.response.AiChatResponse;
import com.teamfp.aistock.domain.ai.entity.AiPlanningMessage;
import com.teamfp.aistock.domain.ai.entity.AiPlanningSession;
import com.teamfp.aistock.domain.ai.entity.MessageRole;
import com.teamfp.aistock.domain.ai.repository.AiPlanningMessageRepository;
import com.teamfp.aistock.domain.ai.repository.AiPlanningSessionRepository;
import com.teamfp.aistock.domain.ai.service.AiPlanningService;
import com.teamfp.aistock.domain.order.service.HoldingValuationService;
import com.teamfp.aistock.domain.user.entity.Role;
import com.teamfp.aistock.domain.user.entity.User;
import com.teamfp.aistock.domain.user.repository.InvestmentProfileRepository;
import com.teamfp.aistock.domain.user.repository.UserRepository;
import com.teamfp.aistock.global.redis.RedisAiToolCacheService;
import com.teamfp.aistock.global.redis.RedisRateLimiterService;
import com.teamfp.aistock.infra.dart.DartApiClient;
import com.teamfp.aistock.infra.gemini.GeminiApiClient;
import com.teamfp.aistock.infra.ls.LsAccessTokenProvider;
import com.teamfp.aistock.infra.ls.LsHighItemApiClient;
import com.teamfp.aistock.infra.ls.LsInvestInfoApiClient;
import com.teamfp.aistock.infra.ls.LsInvestorTrendApiClient;
import com.teamfp.aistock.infra.ls.LsMarketDataApiClient;
import com.teamfp.aistock.infra.ls.LsSectorApiClient;
import com.teamfp.aistock.infra.naver.NaverNewsApiClient;

/**
 * 일회성 수동 라이브 점검용 — 세션별 대화 기록을 파일에 이어 쓰기 때문에, 이미 답한 턴은
 * 다시 Gemini를 호출하지 않고 이번에 새로 추가한 질문 하나만 호출한다. CI 자동 실행 금지,
 * 확인 후 삭제 예정.
 *
 * 사용법: SESSION_NUMBER/NEW_QUESTION만 바꿔서 매번 재실행한다. 새 세션(새 큰 주제)으로
 * 넘어가고 싶으면 SESSION_NUMBER를 올린다.
 */
class LiveGeminiManualCheck {

    private static final Long USER_ID = 1L;
    private static final int SESSION_NUMBER = 29;
    private static final String NEW_QUESTION = "근데 내 의도는 이게아니라 다른 대기업의 인수합병사례 를 물어본거긴해";

    private static final Long SESSION_ID = (long) (10 + SESSION_NUMBER);
    // 개인 PC 경로 대신 OS 임시 디렉터리를 사용 — 실행하는 사람의 환경에 상관없이 동작한다.
    private static final Path TRANSCRIPT_PATH = Path.of(System.getProperty("java.io.tmpdir"),
            "ai-stock-live-gemini-check", "chat-session-" + SESSION_NUMBER + ".log");

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_LIVE_GEMINI_TEST", matches = "true")
    void checkRealConversationTurn() throws IOException {
        AiPlanningSessionRepository sessionRepository = mock(AiPlanningSessionRepository.class);
        AiPlanningMessageRepository messageRepository = mock(AiPlanningMessageRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        InvestmentProfileRepository investmentProfileRepository = mock(InvestmentProfileRepository.class);
        AccountService accountService = mock(AccountService.class);
        HoldingValuationService holdingValuationService = mock(HoldingValuationService.class);

        User user = User.builder().loginId("tester").name("테스터").role(Role.USER).isActive(true).build();
        AiPlanningSession session = AiPlanningSession.builder().user(user).build();
        when(sessionRepository.findByUserIdAndSessionId(USER_ID, SESSION_ID)).thenReturn(Optional.of(session));
        when(accountService.getMyAccounts(USER_ID)).thenReturn(List.of());
        when(investmentProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());

        // 이전 실행에서 저장해둔 대화 기록을 파일에서 복원한다 — 그래야 이미 답한 턴을
        // 다시 Gemini에 물어보지 않고, 이번 질문 하나만 새로 호출한다.
        List<AiPlanningMessage> storedMessages = new ArrayList<>();
        int previousTurnCount = 0;
        if (Files.exists(TRANSCRIPT_PATH)) {
            List<String> lines = Files.readAllLines(TRANSCRIPT_PATH, StandardCharsets.UTF_8);
            for (String line : lines) {
                int sep = line.indexOf('|');
                MessageRole role = MessageRole.valueOf(line.substring(0, sep));
                String content = line.substring(sep + 1).replace("\\n", "\n");
                storedMessages.add(AiPlanningMessage.builder().session(session).role(role).content(content).build());
                if (role == MessageRole.USER) {
                    previousTurnCount++;
                }
            }
        }

        when(messageRepository.save(any())).thenAnswer(invocation -> {
            AiPlanningMessage message = invocation.getArgument(0);
            storedMessages.add(message);
            return message;
        });
        when(messageRepository.findRecentBySessionId(anyLong(), any())).thenAnswer(invocation -> {
            List<AiPlanningMessage> recentDesc = new ArrayList<>(storedMessages);
            Collections.reverse(recentDesc);
            return recentDesc;
        });

        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory("localhost", 6379);
        connectionFactory.afterPropertiesSet();
        RedisTemplate<String, String> redisTemplate = new RedisTemplate<>();
        redisTemplate.setConnectionFactory(connectionFactory);
        redisTemplate.setKeySerializer(new StringRedisSerializer());
        redisTemplate.setValueSerializer(new StringRedisSerializer());
        redisTemplate.setHashKeySerializer(new StringRedisSerializer());
        redisTemplate.setHashValueSerializer(new StringRedisSerializer());
        redisTemplate.afterPropertiesSet();

        RedisRateLimiterService rateLimiterService = new RedisRateLimiterService(redisTemplate);
        RedisAiToolCacheService aiToolCacheService = new RedisAiToolCacheService(redisTemplate);

        GeminiApiClient geminiApiClient = new GeminiApiClient(RestClient.builder());
        ReflectionTestUtils.setField(geminiApiClient, "apiKey", System.getenv("GEMINI_API_KEY"));
        ReflectionTestUtils.setField(geminiApiClient, "judgeApiUrl",
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-lite:generateContent");
        ReflectionTestUtils.setField(geminiApiClient, "answerApiUrl",
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-lite:generateContent");

        DartApiClient dartApiClient = new DartApiClient(RestClient.builder());
        ReflectionTestUtils.setField(dartApiClient, "apiKey", System.getenv("DART_API_KEY"));
        ReflectionTestUtils.setField(dartApiClient, "apiUrl", "https://opendart.fss.or.kr/api/fnlttSinglAcntAll.json");
        ReflectionTestUtils.setField(dartApiClient, "corpCodeUrl", "https://opendart.fss.or.kr/api/corpCode.xml");

        NaverNewsApiClient naverNewsApiClient = new NaverNewsApiClient(RestClient.builder());
        ReflectionTestUtils.setField(naverNewsApiClient, "clientId", System.getenv("NAVER_CLIENT_ID"));
        ReflectionTestUtils.setField(naverNewsApiClient, "clientSecret", System.getenv("NAVER_CLIENT_SECRET"));
        ReflectionTestUtils.setField(naverNewsApiClient, "apiUrl", "https://naverapihub.apigw.ntruss.com/search/v1/news");

        // LsAccessTokenProvider(2026-08-10 분리) — LsMarketDataApiClient/LsInvestorTrendApiClient/
        // LsInvestInfoApiClient 3개가 공유하는 토큰 발급 컴포넌트. 이 셋이 전부 같은 인스턴스를
        // 주입받아야 실제로도 동일한 발급 로직을 타는 실제 서비스 구성과 같아진다.
        LsAccessTokenProvider lsAccessTokenProvider = new LsAccessTokenProvider(RestClient.builder());
        ReflectionTestUtils.setField(lsAccessTokenProvider, "tokenUrl", "https://openapi.ls-sec.co.kr:8080/oauth2/token");
        ReflectionTestUtils.setField(lsAccessTokenProvider, "appKey", System.getenv("LS_APP_KEY"));
        ReflectionTestUtils.setField(lsAccessTokenProvider, "appSecret", System.getenv("LS_APP_SECRET"));

        LsMarketDataApiClient lsMarketDataApiClient = new LsMarketDataApiClient(lsAccessTokenProvider, RestClient.builder());
        ReflectionTestUtils.setField(lsMarketDataApiClient, "marketDataUrl", "https://openapi.ls-sec.co.kr:8080/stock/market-data");

        LsInvestorTrendApiClient lsInvestorTrendApiClient = new LsInvestorTrendApiClient(lsAccessTokenProvider, RestClient.builder());
        ReflectionTestUtils.setField(lsInvestorTrendApiClient, "frgrIttUrl", "https://openapi.ls-sec.co.kr:8080/stock/frgr-itt");

        LsInvestInfoApiClient lsInvestInfoApiClient = new LsInvestInfoApiClient(lsAccessTokenProvider, RestClient.builder());
        ReflectionTestUtils.setField(lsInvestInfoApiClient, "investInfoUrl", "https://openapi.ls-sec.co.kr:8080/stock/investinfo");

        // 2026-08-11 추가 — get_market_ranking/get_theme_info 도구 전용.
        LsHighItemApiClient lsHighItemApiClient = new LsHighItemApiClient(lsAccessTokenProvider, RestClient.builder());
        ReflectionTestUtils.setField(lsHighItemApiClient, "highItemUrl", "https://openapi.ls-sec.co.kr:8080/stock/high-item");

        LsSectorApiClient lsSectorApiClient = new LsSectorApiClient(lsAccessTokenProvider, RestClient.builder());
        ReflectionTestUtils.setField(lsSectorApiClient, "sectorUrl", "https://openapi.ls-sec.co.kr:8080/stock/sector");

        // 2026-08-11 추가 — 나머지 13개 도구 전용 클라이언트.
        com.teamfp.aistock.infra.ls.LsEtfApiClient lsEtfApiClient =
                new com.teamfp.aistock.infra.ls.LsEtfApiClient(lsAccessTokenProvider, RestClient.builder());
        ReflectionTestUtils.setField(lsEtfApiClient, "etfUrl", "https://openapi.ls-sec.co.kr:8080/stock/etf");

        com.teamfp.aistock.infra.ls.LsProgramApiClient lsProgramApiClient =
                new com.teamfp.aistock.infra.ls.LsProgramApiClient(lsAccessTokenProvider, RestClient.builder());
        ReflectionTestUtils.setField(lsProgramApiClient, "programUrl", "https://openapi.ls-sec.co.kr:8080/stock/program");

        com.teamfp.aistock.infra.ls.LsInvestorApiClient lsInvestorApiClient =
                new com.teamfp.aistock.infra.ls.LsInvestorApiClient(lsAccessTokenProvider, RestClient.builder());
        ReflectionTestUtils.setField(lsInvestorApiClient, "investorUrl", "https://openapi.ls-sec.co.kr:8080/stock/investor");

        com.teamfp.aistock.infra.ls.LsEtcApiClient lsEtcApiClient =
                new com.teamfp.aistock.infra.ls.LsEtcApiClient(lsAccessTokenProvider, RestClient.builder());
        ReflectionTestUtils.setField(lsEtcApiClient, "etcUrl", "https://openapi.ls-sec.co.kr:8080/stock/etc");

        com.teamfp.aistock.infra.ls.LsIndustryApiClient lsIndustryApiClient =
                new com.teamfp.aistock.infra.ls.LsIndustryApiClient(lsAccessTokenProvider, RestClient.builder());
        ReflectionTestUtils.setField(lsIndustryApiClient, "industryUrl", "https://openapi.ls-sec.co.kr:8080/indtp/market-data");

        Executor syncExecutor = Runnable::run;

        AiPlanningService aiPlanningService = new AiPlanningService(
                org.mockito.Mockito.mock(com.teamfp.aistock.domain.ai.service.PlanningPreferencesService.class),
                sessionRepository, messageRepository, userRepository, investmentProfileRepository,
                accountService, holdingValuationService, rateLimiterService, aiToolCacheService,
                geminiApiClient, dartApiClient, naverNewsApiClient, lsMarketDataApiClient,
                lsInvestorTrendApiClient, lsInvestInfoApiClient, lsHighItemApiClient, lsSectorApiClient,
                lsEtfApiClient, lsProgramApiClient, lsInvestorApiClient, lsEtcApiClient, lsIndustryApiClient,
                syncExecutor);
        ReflectionTestUtils.setField(aiPlanningService, "self", aiPlanningService);

        System.out.println("### 세션 " + SESSION_NUMBER + " / 턴 " + (previousTurnCount + 1) + " ###");
        System.out.println("=== 질문 ===");
        System.out.println(NEW_QUESTION);
        AiChatResponse response = aiPlanningService.sendMessage(USER_ID, SESSION_ID, new AiChatRequest(NEW_QUESTION));
        System.out.println("=== 답변 ===");
        System.out.println(response.content());

        // 이번 턴까지 포함해서 파일에 다시 저장 — 다음 실행 때 이어받는다.
        List<String> outLines = new ArrayList<>();
        for (AiPlanningMessage message : storedMessages) {
            outLines.add(message.getRole() + "|" + message.getContent().replace("\n", "\\n"));
        }
        Files.createDirectories(TRANSCRIPT_PATH.getParent());
        Files.write(TRANSCRIPT_PATH, outLines, StandardCharsets.UTF_8);
    }
}
