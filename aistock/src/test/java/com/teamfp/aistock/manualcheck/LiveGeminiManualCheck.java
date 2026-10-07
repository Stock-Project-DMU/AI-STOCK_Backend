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
import com.teamfp.aistock.infra.dart.DartApiClient;
import com.teamfp.aistock.infra.gemini.GeminiApiClient;
import com.teamfp.aistock.infra.marketdata.HighItemApiClient;
import com.teamfp.aistock.infra.marketdata.InvestInfoApiClient;
import com.teamfp.aistock.infra.marketdata.InvestorTrendApiClient;
import com.teamfp.aistock.infra.marketdata.MarketDataApiClient;
import com.teamfp.aistock.infra.marketdata.SectorApiClient;
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

        // 시세 클라이언트는 전부 local-market-data-generator의 market_data.json을 읽는다(fix/local-market-data-stable).
        // MARKET_DATA_PATH 환경변수(없으면 기본 상대경로)의 디렉토리를 쓴다.
        com.teamfp.aistock.infra.marketdata.LocalMarketDataReader localMarketDataReader =
                new com.teamfp.aistock.infra.marketdata.LocalMarketDataReader();
        ReflectionTestUtils.setField(localMarketDataReader, "localDataPath",
                System.getenv().getOrDefault("MARKET_DATA_PATH", "../../local-market-data-generator/output"));
        MarketDataApiClient marketDataApiClient = new MarketDataApiClient(localMarketDataReader);
        InvestorTrendApiClient investorTrendApiClient = new InvestorTrendApiClient(localMarketDataReader);
        InvestInfoApiClient investInfoApiClient = new InvestInfoApiClient(localMarketDataReader);
        HighItemApiClient highItemApiClient = new HighItemApiClient(localMarketDataReader);
        SectorApiClient sectorApiClient = new SectorApiClient(localMarketDataReader);
        com.teamfp.aistock.infra.marketdata.EtfApiClient etfApiClient =
                new com.teamfp.aistock.infra.marketdata.EtfApiClient(localMarketDataReader);
        com.teamfp.aistock.infra.marketdata.ProgramApiClient programApiClient =
                new com.teamfp.aistock.infra.marketdata.ProgramApiClient(localMarketDataReader);
        com.teamfp.aistock.infra.marketdata.InvestorApiClient investorApiClient =
                new com.teamfp.aistock.infra.marketdata.InvestorApiClient(localMarketDataReader);
        com.teamfp.aistock.infra.marketdata.EtcApiClient etcApiClient =
                new com.teamfp.aistock.infra.marketdata.EtcApiClient(localMarketDataReader);
        com.teamfp.aistock.infra.marketdata.IndustryApiClient industryApiClient =
                new com.teamfp.aistock.infra.marketdata.IndustryApiClient(localMarketDataReader);

        Executor syncExecutor = Runnable::run;

        AiPlanningService aiPlanningService = new AiPlanningService(
                org.mockito.Mockito.mock(com.teamfp.aistock.domain.ai.service.PlanningPreferencesService.class),
                sessionRepository, messageRepository, userRepository, investmentProfileRepository,
                accountService, holdingValuationService, aiToolCacheService,
                geminiApiClient, dartApiClient, naverNewsApiClient, marketDataApiClient,
                investorTrendApiClient, investInfoApiClient, highItemApiClient, sectorApiClient,
                etfApiClient, programApiClient, investorApiClient, etcApiClient, industryApiClient,
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
