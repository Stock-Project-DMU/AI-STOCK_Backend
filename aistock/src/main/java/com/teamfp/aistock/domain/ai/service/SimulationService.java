package com.teamfp.aistock.domain.ai.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import com.teamfp.aistock.domain.ai.dto.ScenarioDataJson;
import com.teamfp.aistock.domain.ai.dto.ScenarioSetDto;
import com.teamfp.aistock.domain.ai.dto.request.SimulationRequest;
import com.teamfp.aistock.domain.ai.dto.response.SimulationResponse;
import com.teamfp.aistock.domain.ai.entity.Simulation;
import com.teamfp.aistock.domain.ai.repository.SimulationRepository;
import com.teamfp.aistock.domain.notification.entity.NotificationType;
import com.teamfp.aistock.domain.notification.service.NotificationService;
import com.teamfp.aistock.domain.stock.service.StockNameResolver;
import com.teamfp.aistock.domain.user.entity.User;
import com.teamfp.aistock.domain.user.repository.UserRepository;
import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;
import com.teamfp.aistock.global.redis.RedisRateLimiterService;
import com.teamfp.aistock.infra.dart.DartApiClient;
import com.teamfp.aistock.infra.dart.dto.DartFinancialRequest;
import com.teamfp.aistock.infra.dart.dto.DartFinancialResponse;
import com.teamfp.aistock.infra.gemini.GeminiApiClient;
import com.teamfp.aistock.infra.gemini.dto.GeminiRequest;
import com.teamfp.aistock.infra.gemini.dto.GeminiResponse;
import com.teamfp.aistock.infra.naver.NaverNewsApiClient;
import com.teamfp.aistock.infra.naver.dto.NaverNewsSearchRequest;
import com.teamfp.aistock.infra.naver.dto.NaverNewsSearchResponse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * feature/simulation 1차 PR 범위: 조회만 제공했다. runSimulation(Gemini/DART/뉴스 연동)은
 * feature/ai-planning이 dev에 병합된 뒤 이 브랜치(feature/simulation-integration)에서 이어간다
 * (NAMING.md 8-11절 참고).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SimulationService {

    // 연간 재무제표는 사업연도가 끝난 다음 해에야 공시되므로 "작년" 연간을 조회한다 —
    // AiPlanningService.DART_YEAR_OFFSET/DartApiClient.ANNUAL_FALLBACK_YEAR_OFFSET과 동일한 값을
    // 같은 이유로 이 클래스에도 둔다(간단한 상수라 그 두 곳도 이미 각자 중복해서 갖고 있음).
    private static final int DART_YEAR_OFFSET = 1;

    private static final String SCENARIO_SYSTEM_INSTRUCTION = """
            당신은 모의투자 플랫폼 AI STOCK의 시나리오 분석가입니다. 주어진 종목의 향후 전망을 \
            반영해 월 단위 복리 성장률을 best(낙관)/base(중립)/worst(비관) 세 시나리오로 추정하세요. \
            각 값은 한 달 동안의 복리 성장률을 소수로 표현합니다(예: 월 3% 성장이면 0.03, 월 2% \
            하락이면 -0.02). 다른 설명 없이 아래 JSON 형식으로만 답하세요.
            {"bestMonthlyGrowthRate": 0.0, "baseMonthlyGrowthRate": 0.0, "worstMonthlyGrowthRate": 0.0, "reason": "근거 설명"}
            """;

    private final SimulationRepository simulationRepository;
    private final UserRepository userRepository;
    private final StockNameResolver stockNameResolver;
    private final DartApiClient dartApiClient;
    private final NaverNewsApiClient naverNewsApiClient;
    private final GeminiApiClient geminiApiClient;
    private final RedisRateLimiterService rateLimiterService;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    // resolveStockName()/saveSimulation()의 @Transactional은 Spring AOP 프록시를 거쳐야만 실제로
    // 적용된다. runSimulation()이 같은 클래스 안에서 this.resolveStockName(...)/this.saveSimulation(...)을
    // 그냥 호출하면(self-invocation) 프록시를 우회해 트랜잭션이 걸리지 않는다.
    // AiPlanningService.self와 동일한 패턴으로, @Lazy 필드 주입으로 받은 프록시(자기 자신)를 통해
    // self.xxx(...)로 호출해야 한다.
    @Autowired
    @Lazy
    private SimulationService self;

    @Transactional(readOnly = true)
    public List<SimulationResponse> getMySimulations(Long userId) {
        return simulationRepository.findAllByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * 시뮬레이션 상세 조회. findByUserIdAndSimulationId로 소유권을 함께 검증하여
     * 다른 사용자의 시뮬레이션을 조회할 수 없도록 한다.
     */
    @Transactional(readOnly = true)
    public SimulationResponse getSimulation(Long userId, Long simulationId) {
        Simulation simulation = simulationRepository.findByUserIdAndSimulationId(userId, simulationId)
                .orElseThrow(() -> new CustomException(ErrorCode.SIMULATION_NOT_FOUND));

        return toResponse(simulation);
    }

    /**
     * 목표 도달 시뮬레이션 실행. 순서: ① Gemini 호출 한도 확인 ② Gemini로 시나리오별 월 복리
     * 성장률 추정 ③ DART 재무 데이터 조회(연간+최근분기 병렬) ④ 관련 뉴스 조회 ⑤ 서버가 직접
     * 복리 계산(ScenarioCalculator) ⑥ 결과 저장.
     *
     * 이 메서드에는 일부러 @Transactional을 걸지 않는다 — AiPlanningService.sendMessage()와 동일한
     * 이유. Gemini/DART/네이버 외부 API 호출이 수 초씩 걸릴 수 있는데, 그 구간까지 하나의 DB
     * 트랜잭션(커넥션)으로 묶으면 동시 요청이 몰릴 때 커넥션 풀이 고갈되어 로그인·주문처럼 무관한
     * API까지 영향을 받을 수 있다. self.resolveStockName()의 읽기 전용 조회(사용자 존재 확인 겸
     * 종목명 조회) → 외부 API 호출(트랜잭션 없음) → self.saveSimulation()의 쓰기(저장 + 알림
     * 발송)로 구간을 나눈다.
     */
    public SimulationResponse runSimulation(Long userId, SimulationRequest request) {
        if (!rateLimiterService.isAllowed(userId)) {
            throw new CustomException(ErrorCode.GEMINI_RATE_LIMIT_EXCEEDED);
        }
        // RedisRateLimiterService 계약: isAllowed() 통과 "직후"에 increment한다
        // (AiPlanningService.sendMessage()와 동일한 이유 — 한도 직전 동시 요청의 우회 방지).
        rateLimiterService.increment(userId);

        String stockName = self.resolveStockName(userId, request.stockCode());

        MonthlyGrowthRates growthRates = requestMonthlyGrowthRates(request.stockCode(), stockName);

        String dartDataJson = fetchDartData(stockName);
        String newsDataJson = fetchNewsData(stockName);

        ScenarioSetDto scenarioSet = ScenarioCalculator.calculate(
                request.investmentAmount(),
                growthRates.bestMonthlyGrowthRate(),
                growthRates.baseMonthlyGrowthRate(),
                growthRates.worstMonthlyGrowthRate(),
                request.targetAmount(),
                request.targetMonths(),
                LocalDate.now());

        return self.saveSimulation(userId, request, stockName, scenarioSet, dartDataJson, newsDataJson);
    }

    /**
     * 사용자 존재 확인과 종목명 조회를 외부 API 호출 "전"에 읽기 전용 트랜잭션으로 끝낸다 —
     * AiPlanningService.loadHistory()와 동일한 이유(유효하지 않은 요청에 Gemini/DART/네이버 호출을
     * 낭비하지 않기 위함). runSimulation()이 self 프록시를 통해서만 호출해야 @Transactional이
     * 적용된다.
     */
    @Transactional(readOnly = true)
    public String resolveStockName(Long userId, String stockCode) {
        if (!userRepository.existsById(userId)) {
            throw new CustomException(ErrorCode.USER_NOT_FOUND);
        }

        String resolved = stockNameResolver.resolveStockName(stockCode);
        // StockBroadcastService.resolveStockName()과 동일한 폴백 — 어디에도 등록된 적 없는
        // 종목이면 종목코드를 이름 대신 사용한다.
        return resolved != null ? resolved : stockCode;
    }

    /**
     * 외부 API 호출이 전부 끝난 뒤에만 호출된다. Simulation 저장과 SIMULATION 알림 발송을 한
     * 트랜잭션에서 함께 처리해, 저장에 실패하면 알림도 함께 롤백되게 한다 — saveTurn()과 동일한
     * 원칙. runSimulation()이 self 프록시를 통해서만 호출해야 @Transactional이 적용된다.
     * userRepository.getReferenceById()는 resolveStockName()에서 이미 존재를 확인했으므로,
     * NotificationService.notify()와 동일하게 추가 SELECT 없이 참조만으로 FK를 채운다.
     */
    @Transactional
    public SimulationResponse saveSimulation(Long userId, SimulationRequest request, String stockName,
                                              ScenarioSetDto scenarioSet, String dartDataJson, String newsDataJson) {
        User user = userRepository.getReferenceById(userId);

        Simulation simulation = Simulation.builder()
                .user(user)
                .stockCode(request.stockCode())
                .stockName(stockName)
                .investmentAmount(request.investmentAmount())
                .targetAmount(request.targetAmount())
                .targetMonths(request.targetMonths())
                .scenarioData(serializeScenarioData(scenarioSet))
                .bestReachDate(scenarioSet.bestReachDate())
                .baseReachDate(scenarioSet.baseReachDate())
                .worstReachDate(scenarioSet.worstReachDate())
                .dartData(dartDataJson)
                .newsData(newsDataJson)
                .build();
        simulationRepository.save(simulation);

        // AiNewsService.generateBriefingForUser()와 동일한 패턴 — notify()가 그 자체로
        // @Transactional이라 이 저장 트랜잭션에 참여하며, STOMP 유니캐스팅은 커밋 후로 미뤄진다.
        notificationService.notify(userId, NotificationType.SIMULATION,
                stockName + " 목표 도달 시뮬레이션이 완료됐어요",
                buildReachDateNotificationContent(scenarioSet.baseReachDate()));

        return SimulationResponse.of(simulation, scenarioSet.bestPoints(), scenarioSet.basePoints(), scenarioSet.worstPoints());
    }

    // 베이스 시나리오 기준 목표 도달 예상일을 알림 본문으로 만든다. targetMonths 안에 도달하지
    // 못하면(ScenarioCalculator.findReachDate() 참고) baseReachDate가 null이므로 안내 문구로 대체한다.
    private String buildReachDateNotificationContent(LocalDate baseReachDate) {
        if (baseReachDate == null) {
            return "베이스 시나리오 기준으로는 설정하신 기간 내 목표 도달이 어려울 것으로 예상돼요.";
        }
        return "베이스 시나리오 기준 목표 도달 예상일: " + baseReachDate;
    }

    private MonthlyGrowthRates requestMonthlyGrowthRates(String stockCode, String stockName) {
        String prompt = "종목 코드: %s, 종목명: %s".formatted(stockCode, stockName);
        GeminiRequest geminiRequest = new GeminiRequest(
                SCENARIO_SYSTEM_INSTRUCTION, prompt, List.of(), List.of(), List.of(), GeminiRequest.GeminiModel.ANSWER);
        GeminiResponse response = geminiApiClient.generate(geminiRequest);
        return parseGrowthRates(response.content());
    }

    private MonthlyGrowthRates parseGrowthRates(String content) {
        try {
            return objectMapper.readValue(stripJsonFence(content), MonthlyGrowthRates.class);
        } catch (JacksonException e) {
            throw new CustomException(ErrorCode.SCENARIO_DATA_PARSE_ERROR, e);
        }
    }

    // Gemini가 지침을 어기고 ```json ... ``` 코드펜스로 감싸 답하는 경우를 방어한다.
    private String stripJsonFence(String content) {
        if (content == null) {
            return "";
        }
        String trimmed = content.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceFirst("^```[a-zA-Z]*\\s*", "").replaceFirst("```\\s*$", "").trim();
        }
        return trimmed;
    }

    /**
     * DART 재무 데이터(연간+최근분기)를 병렬 조회한다. corpCode를 못 찾으면(비상장 등) null을
     * 반환한다 — simulations.dart_data는 NULL 허용 컬럼이라 조회 불가를 실패로 취급하지 않는다.
     * 두 호출은 서로 독립적이라 DartApiClient.fetchFinancialIndicatorCategories()와 동일하게
     * 가상 스레드로 동시에 실행한다.
     */
    private String fetchDartData(String stockName) {
        Optional<String> corpCode = dartApiClient.resolveCorpCodeByName(stockName);
        if (corpCode.isEmpty()) {
            log.debug("DART corpCode를 찾지 못해 dartData 없이 진행 - stockName: {}", stockName);
            return null;
        }

        try (var virtualExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<DartFinancialResponse> annualFuture = CompletableFuture.supplyAsync(
                    () -> dartApiClient.getFinancials(new DartFinancialRequest(corpCode.get(), LocalDate.now().getYear() - DART_YEAR_OFFSET)),
                    virtualExecutor);
            CompletableFuture<DartFinancialResponse> quarterlyFuture = CompletableFuture.supplyAsync(
                    () -> dartApiClient.getRecentQuarterlyFinancials(corpCode.get()),
                    virtualExecutor);

            DartDataSnapshot snapshot = new DartDataSnapshot(annualFuture.join(), quarterlyFuture.join());
            return serializeDartData(snapshot);
        }
    }

    private String fetchNewsData(String stockName) {
        NaverNewsSearchResponse newsResponse = naverNewsApiClient.search(new NaverNewsSearchRequest(stockName, null, null));
        return serializeNewsData(newsResponse);
    }

    private SimulationResponse toResponse(Simulation simulation) {
        ScenarioDataJson scenarioData = parseScenarioData(simulation.getScenarioData());
        return SimulationResponse.of(simulation, scenarioData.best(), scenarioData.base(), scenarioData.worst());
    }

    /**
     * MySQL JSON 컬럼(scenario_data) 파싱 실패는 RedisStockCacheService의 Redis 직렬화
     * 오류 처리와 성격이 같지만(체크 예외를 CustomException으로 감싸 던짐), Redis가 아니라
     * DB 컬럼 파싱이므로 REDIS_SERIALIZATION_ERROR를 재사용하지 않고 별도 코드를 쓴다.
     */
    private ScenarioDataJson parseScenarioData(String scenarioDataJson) {
        try {
            return objectMapper.readValue(scenarioDataJson, ScenarioDataJson.class);
        } catch (JacksonException e) {
            throw new CustomException(ErrorCode.SCENARIO_DATA_PARSE_ERROR, e);
        }
    }

    private String serializeScenarioData(ScenarioSetDto scenarioSet) {
        try {
            return objectMapper.writeValueAsString(
                    new ScenarioDataJson(scenarioSet.bestPoints(), scenarioSet.basePoints(), scenarioSet.worstPoints()));
        } catch (JacksonException e) {
            throw new CustomException(ErrorCode.SCENARIO_DATA_PARSE_ERROR, e);
        }
    }

    private String serializeDartData(DartDataSnapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JacksonException e) {
            throw new CustomException(ErrorCode.SCENARIO_DATA_PARSE_ERROR, e);
        }
    }

    private String serializeNewsData(NaverNewsSearchResponse newsResponse) {
        try {
            return objectMapper.writeValueAsString(newsResponse);
        } catch (JacksonException e) {
            throw new CustomException(ErrorCode.SCENARIO_DATA_PARSE_ERROR, e);
        }
    }

    // Gemini generate() 응답 텍스트를 파싱하는 내부 전용 타입 — GeminiApiClient의 private
    // record GeminiApiResponse와 동일한 성격(NAMING.md에 올리지 않는 구현 세부사항).
    // reason은 프롬프트에 근거를 함께 요청해 답변 품질을 끌어올리기 위한 것으로, 저장하지는
    // 않는다. ignoreUnknown은 Gemini가 지침을 어기고 여분의 필드를 더 얹어 보내도 파싱이
    // 실패하지 않도록 하는 방어다.
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record MonthlyGrowthRates(
            double bestMonthlyGrowthRate,
            double baseMonthlyGrowthRate,
            double worstMonthlyGrowthRate,
            String reason
    ) {
    }

    // simulations.dart_data 컬럼에 그대로 저장할 원본 스냅샷 — DartFinancialResponse는 이미
    // record라 별도 변환 없이 그대로 담는다.
    private record DartDataSnapshot(
            DartFinancialResponse annual,
            DartFinancialResponse recentQuarterly
    ) {
    }
}
