package com.teamfp.aistock.domain.ai.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import com.teamfp.aistock.domain.account.dto.response.AccountInfoResponse;
import com.teamfp.aistock.domain.account.service.AccountService;
import com.teamfp.aistock.domain.ai.dto.PendingSimulationDto;
import com.teamfp.aistock.domain.ai.dto.PortfolioAllocationDto;
import com.teamfp.aistock.domain.ai.dto.PortfolioProjectionDto;
import com.teamfp.aistock.domain.ai.dto.ProjectionDataJson;
import com.teamfp.aistock.domain.ai.dto.ScenarioPointDto;
import com.teamfp.aistock.domain.ai.dto.request.SaveSimulationRequest;
import com.teamfp.aistock.domain.ai.dto.request.SimulationRequest;
import com.teamfp.aistock.domain.ai.dto.response.SimulationResponse;
import com.teamfp.aistock.domain.ai.dto.response.SimulationSummaryResponse;
import com.teamfp.aistock.domain.ai.entity.Simulation;
import com.teamfp.aistock.domain.ai.repository.SimulationRepository;
import com.teamfp.aistock.domain.notification.entity.NotificationType;
import com.teamfp.aistock.domain.notification.service.NotificationService;
import com.teamfp.aistock.domain.order.dto.HoldingValuationDto;
import com.teamfp.aistock.domain.order.service.HoldingValuationService;
import com.teamfp.aistock.domain.stock.service.MarketQueryService;
import com.teamfp.aistock.domain.user.entity.InvestmentProfile;
import com.teamfp.aistock.domain.user.entity.User;
import com.teamfp.aistock.domain.user.repository.InvestmentProfileRepository;
import com.teamfp.aistock.domain.user.repository.UserRepository;
import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;
import com.teamfp.aistock.global.redis.RedisPendingSimulationService;
import com.teamfp.aistock.global.redis.RedisRateLimiterService;
import com.teamfp.aistock.infra.dart.DartApiClient;
import com.teamfp.aistock.infra.dart.dto.DartFinancialRequest;
import com.teamfp.aistock.infra.dart.dto.DartFinancialResponse;
import com.teamfp.aistock.infra.gemini.GeminiApiClient;
import com.teamfp.aistock.infra.gemini.dto.GeminiRequest;
import com.teamfp.aistock.infra.gemini.dto.GeminiResponse;
import com.teamfp.aistock.infra.marketdata.dto.HistoricalPriceDto;
import com.teamfp.aistock.infra.marketdata.dto.RankingItemDto;
import com.teamfp.aistock.infra.naver.NaverNewsApiClient;
import com.teamfp.aistock.infra.naver.dto.NaverNewsSearchRequest;
import com.teamfp.aistock.infra.naver.dto.NaverNewsSearchResponse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 목표 도달 시뮬레이션(feature/goal-simulation-v2, 2026-10-01 — 단일 종목·Gemini 성장률 추정
 * 구조를 "내 보유종목 유지 vs 투자성향 리밸런싱" 비교 구조로 재작성).
 *
 * 실행 순서(runSimulation):
 * ① Gemini 호출 한도 확인(분당3/일일10) ② 투자성향·계좌 확인
 * ③ Gemini 1차 — 자유 문장에서 목표 금액·기한 추출, 성공하면 한도 1회 차감 ④ 보유종목 평가금액 + 예수금 = 시작 금액
 * ⑤ 보유종목별 최근 3년 월봉 → 보수적 월 성장률(ScenarioCalculator) → 왼쪽 차트
 * ⑥ Gemini — 시가총액 상위 종목 목록 안에서 성향에 맞는 리밸런싱 구성 추천 → 서버 검증
 *    (RebalancePlanValidator, 위반 시 1회 재요청) → 시세 이력 12개월 미만 종목 제외 → 오른쪽 차트
 * ⑦ 비중 변화가 큰 상위 5개 종목의 DART·뉴스 조회 ⑧ Gemini 2차 — 리밸런싱 이유·단축 설명
 * ⑨ 결과를 Redis에 30분 보관(저장 버튼을 눌러야 DB에 기록 — saveSimulation)
 *
 * 숫자(성장률·도달 시점·단축 개월)는 전부 서버가 계산하고, Gemini 2차 호출에는 계산이 끝난
 * 숫자를 넘겨 설명만 쓰게 한다 — Gemini가 숫자를 지어내 화면의 차트와 설명이 어긋나는 것을 막는다.
 *
 * runSimulation()에는 일부러 @Transactional을 걸지 않는다 — Gemini/DART/네이버/시세 외부 호출이
 * 수 초씩 걸리는 구간을 DB 트랜잭션으로 묶으면 커넥션 풀이 고갈될 수 있어서다
 * (AiPlanningService.sendMessage()와 같은 이유). 이 메서드는 DB에 쓰지 않으며, 읽기는 각 서비스·
 * Repository 메서드가 자체 트랜잭션으로 짧게 끝낸다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SimulationService {

    // 연간 재무제표는 사업연도가 끝난 다음 해에야 공시되므로 "작년" 연간을 조회한다
    // (AiPlanningService.DART_YEAR_OFFSET과 같은 이유).
    private static final int DART_YEAR_OFFSET = 1;

    private static final int DWMCODE_MONTH = 3;

    // Gemini 2차 설명의 근거로 DART·뉴스를 조회할 종목 수(비중 변화가 큰 순).
    private static final int RATIONALE_STOCK_COUNT = 5;

    // 종목당 Gemini에 넘길 뉴스 기사 수.
    private static final int NEWS_ITEMS_PER_STOCK = 3;

    // 리밸런싱 추천 결과가 규칙을 어기면 1회만 다시 요청한다(총 2회).
    private static final int MAX_REBALANCE_ATTEMPTS = 2;

    private static final String GOAL_EXTRACTION_INSTRUCTION = """
            당신은 모의투자 플랫폼 AI STOCK의 목표 해석기입니다. 사용자가 적은 투자 목표 문장에서 \
            목표 금액(원)과 목표 기한(개월)을 추출하세요.
            - targetAmount: 원 단위 정수. "1억"=100000000, "5천만원"=50000000, "3억 5천"=350000000
            - periodMonths: 개월 수 정수. "3년 안에"=36, "6개월 뒤"=6, "2030년까지"처럼 연도가 오면 \
            오늘 날짜 기준 남은 개월 수. 기한이 없으면 null
            - 목표 금액을 확정할 수 없으면(금액이 없거나 수익률만 있는 경우 등) targetAmount를 null로 두세요.
            다른 설명 없이 아래 JSON 형식으로만 답하세요.
            {"targetAmount": 100000000, "periodMonths": 36}
            """;

    private static final String REBALANCE_INSTRUCTION = """
            당신은 모의투자 플랫폼 AI STOCK의 포트폴리오 리밸런싱 도우미입니다. 사용자의 투자성향과 \
            현재 보유 구성, 목표를 보고 성향에 맞는 포트폴리오를 제안하세요.
            규칙:
            - 종목은 반드시 [후보 종목] 목록에 있는 종목코드만 쓰세요. 현재 보유종목은 비중을 \
            조정해 유지하거나 빼도 되고, 후보 목록의 새 종목을 편입해도 됩니다.
            - 주식 종목은 1개 이상 10개 이하, 종목당 비중은 5~40%입니다.
            - cashWeight는 남겨둘 예수금(현금) 비중(%)입니다. 안정적인 성향일수록 현금 비중을 \
            높게, 공격적인 성향일수록 낮게 두세요.
            - 모든 종목 비중과 cashWeight의 합은 100이어야 합니다.
            다른 설명 없이 아래 JSON 형식으로만 답하세요.
            {"cashWeight": 10, "allocations": [{"stockCode": "005930", "weight": 30}]}
            """;

    private static final String EXPLANATION_INSTRUCTION = """
            당신은 모의투자 플랫폼 AI STOCK의 시뮬레이션 해설가입니다. 서버가 계산한 "현재 보유 유지"와 \
            "리밸런싱" 두 결과를 비교해 설명하세요.
            규칙:
            - 숫자(금액·비중·성장률·개월 수·날짜)는 아래에 주어진 값만 그대로 인용하고, 새로운 숫자를 \
            만들거나 다시 계산하지 마세요.
            - rebalanceReason: 투자성향과 종목별 재무·뉴스 근거를 들어 왜 이렇게 비중을 조정했는지 \
            3~5문장으로 설명하세요. 근거 데이터가 없는 종목은 근거를 지어내지 마세요.
            - timeReductionExplanation: 목표 도달 시점이 얼마나 앞당겨지는지(또는 늦어지거나 달라지지 \
            않는지)와 그 이유를 2~4문장으로 설명하세요. 기한이 있으면 기한 안 달성 여부도 언급하세요.
            - 존댓말로 쓰고, 매수·매도를 직접 권유하는 표현은 쓰지 마세요.
            다른 설명 없이 아래 JSON 형식으로만 답하세요.
            {"rebalanceReason": "...", "timeReductionExplanation": "..."}
            """;

    private final SimulationRepository simulationRepository;
    private final UserRepository userRepository;
    private final InvestmentProfileRepository investmentProfileRepository;
    private final AccountService accountService;
    private final HoldingValuationService holdingValuationService;
    private final MarketQueryService marketQueryService;
    private final DartApiClient dartApiClient;
    private final NaverNewsApiClient naverNewsApiClient;
    private final GeminiApiClient geminiApiClient;
    private final RedisRateLimiterService rateLimiterService;
    private final RedisPendingSimulationService pendingSimulationService;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public List<SimulationSummaryResponse> getMySimulations(Long userId) {
        return simulationRepository.findAllByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(SimulationSummaryResponse::from)
                .toList();
    }

    /**
     * 저장된 시뮬레이션 상세 조회. findByUserIdAndSimulationId로 소유권을 함께 검증하여
     * 다른 사용자의 시뮬레이션을 조회할 수 없도록 한다.
     */
    @Transactional(readOnly = true)
    public SimulationResponse getSimulation(Long userId, Long simulationId) {
        Simulation simulation = findOwnedSimulation(userId, simulationId);
        return SimulationResponse.of(simulation, readJson(simulation.getProjectionData(), ProjectionDataJson.class));
    }

    @Transactional
    public void deleteSimulation(Long userId, Long simulationId) {
        simulationRepository.delete(findOwnedSimulation(userId, simulationId));
    }

    public SimulationResponse runSimulation(Long userId, SimulationRequest request) {
        if (!rateLimiterService.isAllowed(userId)) {
            throw new CustomException(ErrorCode.GEMINI_RATE_LIMIT_EXCEEDED);
        }

        InvestmentProfile profile = investmentProfileRepository.findByUserId(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.INVESTMENT_PROFILE_REQUIRED));
        AccountInfoResponse account = accountService.getMyAccounts(userId).stream().findFirst()
                .orElseThrow(() -> new CustomException(ErrorCode.ACCOUNT_NOT_FOUND));

        GoalExtraction goal = extractGoal(request.goalText());
        // 설문·계좌 확인과 목표 해석이 성공한 뒤에만 1회 차감한다 — 설문 미완료·목표 문장 오타처럼 사용자가
        // 고쳐서 다시 시도할 실패까지 하루 10회 한도에서 빼지 않기 위함이다(2026-10-03). Gemini를 여러 번
        // 부르지만 사용자 기준 "실행 1회"로 1만 올린다. RedisRateLimiterService 계약("isAllowed() 직후
        // increment")보다 늦게 올리므로 한도 직전에 동시에 들어온 요청은 목표 해석 1회만큼 더 통과할 수 있다 —
        // 목표 해석(JUDGE 모델 1회) 비용이라 감수한다.
        rateLimiterService.increment(userId);

        List<HoldingValuationDto> holdings = holdingValuationService.getHoldingValuations(account.accountId()).stream()
                .filter(holding -> holding.quantity() > 0)
                .toList();
        long holdingsAmount = holdings.stream().mapToLong(holding -> holding.quantity() * holding.currentPrice()).sum();
        // 지정가 매수 대기로 묶인 금액(frozenBalance)도 아직 주식이 아닌 현금이므로 예수금에 포함한다.
        long cashAmount = account.balance() + account.frozenBalance();
        long startAmount = holdingsAmount + cashAmount;

        Map<String, List<Long>> monthlyClosesCache = new HashMap<>();

        List<PortfolioAllocationDto> currentAllocations = holdings.stream()
                .map(holding -> new PortfolioAllocationDto(
                        holding.stockCode(),
                        holding.stockName(),
                        startAmount > 0 ? round2(holding.quantity() * holding.currentPrice() * 100.0 / startAmount) : 0,
                        ScenarioCalculator.conservativeMonthlyRate(monthlyCloses(holding.stockCode(), monthlyClosesCache))))
                .toList();
        double currentCashWeight = startAmount > 0 ? round2(cashAmount * 100.0 / startAmount) : 100;

        Map<String, String> stockNames = new LinkedHashMap<>();
        List<RankingItemDto> candidates = marketQueryService.getRankings("market-cap", true);
        candidates.forEach(candidate -> stockNames.putIfAbsent(candidate.getStockCode(), candidate.getStockName()));
        holdings.forEach(holding -> stockNames.putIfAbsent(holding.stockCode(), holding.stockName()));

        RebalancePlanValidator.Result plan = requestRebalance(profile, goal, startAmount, currentCashWeight,
                currentAllocations, candidates, stockNames);

        double rebalancedCashWeight = plan.cashWeight();
        List<PortfolioAllocationDto> rebalancedAllocations = new ArrayList<>();
        List<String> excludedStockNames = new ArrayList<>();
        for (PortfolioAllocationDto allocation : plan.allocations()) {
            List<Long> closes = monthlyCloses(allocation.stockCode(), monthlyClosesCache);
            String stockName = stockNames.getOrDefault(allocation.stockCode(), allocation.stockCode());
            if (ScenarioCalculator.countMonthlyReturns(closes) < ScenarioCalculator.MIN_HISTORY_MONTHS_FOR_REBALANCE) {
                // 시세 이력이 짧은 종목은 빼고, 그 비중은 예수금으로 옮긴다 — 다른 종목에 나눠주면
                // 종목당 40% 상한을 넘길 수 있고, 현금으로 두는 쪽이 보수적이다.
                excludedStockNames.add(stockName);
                rebalancedCashWeight += allocation.weight();
                continue;
            }
            rebalancedAllocations.add(new PortfolioAllocationDto(allocation.stockCode(), stockName, allocation.weight(),
                    ScenarioCalculator.conservativeMonthlyRate(closes)));
        }
        rebalancedCashWeight = round2(rebalancedCashWeight);

        LocalDate today = LocalDate.now();
        double currentRate = ScenarioCalculator.portfolioMonthlyRate(currentAllocations);
        double rebalancedRate = ScenarioCalculator.portfolioMonthlyRate(rebalancedAllocations);
        List<ScenarioPointDto> currentCurve = ScenarioCalculator.projectCurve(startAmount, request.monthlyContribution(),
                currentRate, ScenarioCalculator.MAX_PROJECTION_MONTHS, today);
        List<ScenarioPointDto> rebalancedCurve = ScenarioCalculator.projectCurve(startAmount, request.monthlyContribution(),
                rebalancedRate, ScenarioCalculator.MAX_PROJECTION_MONTHS, today);
        Integer currentReachMonths = ScenarioCalculator.findReachMonths(currentCurve, goal.targetAmount());
        Integer rebalancedReachMonths = ScenarioCalculator.findReachMonths(rebalancedCurve, goal.targetAmount());
        int displayMonths = ScenarioCalculator.displayMonths(goal.periodMonths(), currentReachMonths, rebalancedReachMonths);

        PortfolioProjectionDto current = buildProjection(currentRate, currentCashWeight, currentAllocations, List.of(),
                currentCurve, displayMonths, currentReachMonths, goal.periodMonths());
        PortfolioProjectionDto rebalanced = buildProjection(rebalancedRate, rebalancedCashWeight, rebalancedAllocations,
                excludedStockNames, rebalancedCurve, displayMonths, rebalancedReachMonths, goal.periodMonths());
        Integer shortenedMonths = currentReachMonths != null && rebalancedReachMonths != null
                ? currentReachMonths - rebalancedReachMonths : null;

        Map<String, String> rationaleStocks = selectRationaleStocks(current, rebalanced);
        Map<String, DartDataSnapshot> dartData = fetchDartData(rationaleStocks);
        Map<String, NaverNewsSearchResponse> newsData = fetchNewsData(rationaleStocks);

        SimulationExplanation explanation = requestExplanation(profile, goal, request.monthlyContribution(),
                holdingsAmount, cashAmount, current, rebalanced, shortenedMonths, rationaleStocks, dartData, newsData);

        String pendingSimulationId = UUID.randomUUID().toString();
        SimulationResponse response = new SimulationResponse(
                null,
                pendingSimulationId,
                request.goalText().trim(),
                goal.targetAmount(),
                goal.periodMonths(),
                startAmount,
                holdingsAmount,
                cashAmount,
                request.monthlyContribution(),
                current,
                rebalanced,
                shortenedMonths,
                explanation.rebalanceReason(),
                explanation.timeReductionExplanation(),
                LocalDateTime.now());

        pendingSimulationService.savePending(userId, pendingSimulationId, writeJson(new PendingSimulationDto(
                response,
                dartData.isEmpty() ? null : writeJson(dartData),
                newsData.isEmpty() ? null : writeJson(newsData))));
        return response;
    }

    /**
     * 실행 결과를 DB에 저장한다(저장 버튼). 결과는 요청 본문이 아니라 서버가 Redis에 보관한 값을
     * 쓴다. Redis에서 꺼내는 순간 "저장됨" 표시로 바뀌므로(claimPending, 원자 처리) 같은 결과를 두 번 저장할 수
     * 없고, 두 번째 요청은 만료(SIMULATION_EXPIRED)가 아니라 SIMULATION_ALREADY_SAVED를 받는다(2026-10-03).
     * DB 저장이 실패해 트랜잭션이 롤백되면 원래 결과를 Redis에 되돌려 다시 저장할 수 있게 한다.
     * Simulation 저장과 SIMULATION 알림 발송은 한 트랜잭션으로 묶어, 저장에 실패하면 알림도 롤백된다.
     */
    @Transactional
    public SimulationResponse saveSimulation(Long userId, SaveSimulationRequest request) {
        String pendingJson = pendingSimulationService.claimPending(userId, request.pendingSimulationId())
                .orElseThrow(() -> new CustomException(ErrorCode.SIMULATION_EXPIRED));
        if (RedisPendingSimulationService.SAVED_MARKER.equals(pendingJson)) {
            throw new CustomException(ErrorCode.SIMULATION_ALREADY_SAVED);
        }
        restorePendingOnRollback(userId, request.pendingSimulationId(), pendingJson);
        PendingSimulationDto pending = readJson(pendingJson, PendingSimulationDto.class);
        SimulationResponse result = pending.result();
        ProjectionDataJson projectionData = new ProjectionDataJson(result.holdingsAmount(), result.cashAmount(),
                result.current(), result.rebalanced(), result.shortenedMonths());

        User user = userRepository.getReferenceById(userId);
        Simulation simulation = Simulation.builder()
                .user(user)
                .goalText(result.goalText())
                .targetAmount(result.targetAmount())
                .periodMonths(result.periodMonths())
                .startAmount(result.startAmount())
                .monthlyContribution(result.monthlyContribution())
                .currentReachDate(result.current().reachDate())
                .rebalancedReachDate(result.rebalanced().reachDate())
                .projectionData(writeJson(projectionData))
                .rebalanceReason(result.rebalanceReason())
                .timeReductionExplanation(result.timeReductionExplanation())
                .dartData(pending.dartData())
                .newsData(pending.newsData())
                .build();
        simulationRepository.save(simulation);

        notificationService.notify(userId, NotificationType.SIMULATION,
                "목표 도달 시뮬레이션을 저장했어요",
                buildReachDateNotificationContent(result.rebalanced().reachDate()));

        return SimulationResponse.of(simulation, projectionData);
    }

    // 저장 트랜잭션이 롤백되면 "저장됨" 표시를 원래 실행 결과로 되돌린다. 트랜잭션 밖(단위 테스트 등)에서는
    // 등록할 동기화가 없으므로 건너뛴다.
    private void restorePendingOnRollback(Long userId, String pendingSimulationId, String pendingJson) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    pendingSimulationService.savePending(userId, pendingSimulationId, pendingJson);
                }
            }
        });
    }

    private Simulation findOwnedSimulation(Long userId, Long simulationId) {
        return simulationRepository.findByUserIdAndSimulationId(userId, simulationId)
                .orElseThrow(() -> new CustomException(ErrorCode.SIMULATION_NOT_FOUND));
    }

    // 리밸런싱 기준 목표 도달 예상 시점을 알림 본문으로 만든다. 30년 안에 도달하지 못하면 안내 문구로 대체한다.
    private String buildReachDateNotificationContent(LocalDate rebalancedReachDate) {
        if (rebalancedReachDate == null) {
            return "현재 조건으로는 도달이 어렵습니다.";
        }
        return "리밸런싱 기준 목표 도달 예상 시점: %d년 %d월".formatted(rebalancedReachDate.getYear(), rebalancedReachDate.getMonthValue());
    }

    // ===== ③ 목표 해석 =====

    private GoalExtraction extractGoal(String goalText) {
        String prompt = "오늘 날짜: %s\n목표 문장: %s".formatted(LocalDate.now(), goalText.trim());
        GeminiResponse response = geminiApiClient.generate(new GeminiRequest(GOAL_EXTRACTION_INSTRUCTION, prompt,
                List.of(), List.of(), List.of(), GeminiRequest.GeminiModel.JUDGE));

        GoalExtraction goal;
        try {
            goal = objectMapper.readValue(stripJsonFence(response.content()), GoalExtraction.class);
        } catch (JacksonException e) {
            log.debug("목표 문장 해석 응답 파싱 실패 - content: {}", response.content());
            throw new CustomException(ErrorCode.GOAL_TEXT_PARSE_FAILED);
        }
        if (goal == null || goal.targetAmount() == null || goal.targetAmount() <= 0) {
            throw new CustomException(ErrorCode.GOAL_TEXT_PARSE_FAILED);
        }
        if (goal.periodMonths() != null
                && (goal.periodMonths() < 1 || goal.periodMonths() > ScenarioCalculator.MAX_PROJECTION_MONTHS)) {
            throw new CustomException(ErrorCode.GOAL_PERIOD_OUT_OF_RANGE);
        }
        return goal;
    }

    // ===== ⑤ 시세 이력 =====

    /**
     * 종목의 최근 월봉 종가(최신순, 최대 37개 → 월 수익률 36개). 한 번의 실행 안에서 같은 종목을
     * 현재 보유·리밸런싱 양쪽에서 다시 조회하지 않도록 cache에 담아 재사용한다. 실시간 시세
     * 제공사 초당 호출 한도를 넘기지 않게 병렬이 아니라 순서대로 조회한다.
     * 종목코드 형식이 맞지 않아(INVALID_INPUT) 조회할 수 없는 종목은 이력 없음으로 취급하고,
     * 시세 제공사 장애(MARKET_DATA_UNAVAILABLE 등)는 결과 자체를 믿을 수 없으므로 그대로 전파한다.
     */
    private List<Long> monthlyCloses(String stockCode, Map<String, List<Long>> cache) {
        return cache.computeIfAbsent(stockCode, code -> {
            try {
                return marketQueryService.getChartHistory(code, DWMCODE_MONTH, ScenarioCalculator.HISTORY_MONTHS + 1).stream()
                        .map(HistoricalPriceDto::getClose)
                        .toList();
            } catch (CustomException e) {
                if (e.getErrorCode() != ErrorCode.INVALID_INPUT) {
                    throw e;
                }
                log.debug("시세 이력 조회 불가 종목코드 - stockCode: {}", code);
                return List.of();
            }
        });
    }

    // ===== ⑥ 리밸런싱 추천 =====

    private RebalancePlanValidator.Result requestRebalance(InvestmentProfile profile, GoalExtraction goal, long startAmount,
                                                           double currentCashWeight,
                                                           List<PortfolioAllocationDto> currentAllocations,
                                                           List<RankingItemDto> candidates, Map<String, String> stockNames) {
        String basePrompt = buildRebalancePrompt(profile, goal, startAmount, currentCashWeight, currentAllocations, candidates);
        String prompt = basePrompt;
        Set<String> allowedStockCodes = new HashSet<>(stockNames.keySet());

        for (int attempt = 1; attempt <= MAX_REBALANCE_ATTEMPTS; attempt++) {
            GeminiResponse response = geminiApiClient.generate(new GeminiRequest(REBALANCE_INSTRUCTION, prompt,
                    List.of(), List.of(), List.of(), GeminiRequest.GeminiModel.ANSWER));

            String violation;
            try {
                RebalanceSuggestion suggestion = objectMapper.readValue(stripJsonFence(response.content()), RebalanceSuggestion.class);
                List<PortfolioAllocationDto> suggested = suggestion.allocations() == null ? List.of()
                        : suggestion.allocations().stream()
                        .map(item -> new PortfolioAllocationDto(item.stockCode(), null, item.weight(), 0))
                        .toList();
                RebalancePlanValidator.Result result = RebalancePlanValidator.validate(suggestion.cashWeight(), suggested, allowedStockCodes);
                if (result.valid()) {
                    return result;
                }
                violation = result.violation();
            } catch (JacksonException e) {
                violation = "응답이 지정한 JSON 형식이 아닙니다.";
            }
            log.debug("리밸런싱 추천 검증 실패({}회차) - {}", attempt, violation);
            prompt = basePrompt + "\n\n[이전 답변의 문제]\n" + violation + "\n규칙을 지켜 다시 답하세요.";
        }
        throw new CustomException(ErrorCode.REBALANCE_SUGGESTION_INVALID);
    }

    private String buildRebalancePrompt(InvestmentProfile profile, GoalExtraction goal, long startAmount,
                                        double currentCashWeight, List<PortfolioAllocationDto> currentAllocations,
                                        List<RankingItemDto> candidates) {
        StringBuilder prompt = new StringBuilder();
        prompt.append(describeProfile(profile)).append("\n\n");
        prompt.append("[목표]\n").append(describeGoal(goal)).append("\n\n");
        prompt.append("[현재 구성] 시작 금액 ").append(startAmount).append("원\n");
        if (currentAllocations.isEmpty()) {
            prompt.append("- 보유종목 없음\n");
        }
        currentAllocations.forEach(allocation -> prompt.append("- %s %s: %.2f%%\n"
                .formatted(allocation.stockCode(), allocation.stockName(), allocation.weight())));
        prompt.append("- 예수금: %.2f%%\n\n".formatted(currentCashWeight));
        prompt.append("[후보 종목] (종목코드 종목명)\n");
        candidates.forEach(candidate -> prompt.append(candidate.getStockCode()).append(' ').append(candidate.getStockName()).append('\n'));
        currentAllocations.stream()
                .filter(allocation -> candidates.stream().noneMatch(candidate -> candidate.getStockCode().equals(allocation.stockCode())))
                .forEach(allocation -> prompt.append(allocation.stockCode()).append(' ').append(allocation.stockName()).append(" (현재 보유)\n"));
        return prompt.toString();
    }

    // ===== ⑦ 근거 데이터(DART·뉴스) =====

    /**
     * 현재 → 리밸런싱 사이 비중 변화(절대값)가 큰 순으로 상위 5개 종목(종목코드 → 종목명)을 고른다.
     * 신규 편입(현재 0%)과 전량 제외(리밸런싱 0%)도 변화로 센다.
     */
    private Map<String, String> selectRationaleStocks(PortfolioProjectionDto current, PortfolioProjectionDto rebalanced) {
        Map<String, Double> weightChanges = new HashMap<>();
        Map<String, String> names = new HashMap<>();
        current.allocations().forEach(allocation -> {
            weightChanges.merge(allocation.stockCode(), -allocation.weight(), Double::sum);
            names.put(allocation.stockCode(), allocation.stockName());
        });
        rebalanced.allocations().forEach(allocation -> {
            weightChanges.merge(allocation.stockCode(), allocation.weight(), Double::sum);
            names.put(allocation.stockCode(), allocation.stockName());
        });
        return weightChanges.entrySet().stream()
                .filter(entry -> Math.abs(entry.getValue()) > 0)
                .sorted(Comparator.comparingDouble((Map.Entry<String, Double> entry) -> Math.abs(entry.getValue())).reversed())
                .limit(RATIONALE_STOCK_COUNT)
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> names.get(entry.getKey()),
                        (first, second) -> first, LinkedHashMap::new));
    }

    /**
     * 종목별 DART 재무(연간+최근분기)를 가상 스레드로 병렬 조회한다. 근거 데이터는 설명 품질을
     * 높이는 보조 재료라, 비상장·조회 실패 종목은 빼고 진행한다(결과 맵에 넣지 않음).
     */
    private Map<String, DartDataSnapshot> fetchDartData(Map<String, String> stocks) {
        Map<String, DartDataSnapshot> result = new LinkedHashMap<>();
        try (var virtualExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            Map<String, CompletableFuture<Optional<DartDataSnapshot>>> futures = new LinkedHashMap<>();
            stocks.forEach((stockCode, stockName) -> futures.put(stockCode,
                    CompletableFuture.supplyAsync(() -> fetchDartSnapshot(stockCode, stockName), virtualExecutor)));
            futures.forEach((stockCode, future) -> future.join().ifPresent(snapshot -> result.put(stockCode, snapshot)));
        }
        return result;
    }

    private Optional<DartDataSnapshot> fetchDartSnapshot(String stockCode, String stockName) {
        try {
            Optional<String> corpCode = dartApiClient.resolveCorpCodeByStockCode(stockCode);
            if (corpCode.isEmpty()) {
                return Optional.empty();
            }
            DartFinancialResponse annual = dartApiClient.getFinancials(
                    new DartFinancialRequest(corpCode.get(), LocalDate.now().getYear() - DART_YEAR_OFFSET));
            DartFinancialResponse recentQuarterly = dartApiClient.getRecentQuarterlyFinancials(corpCode.get());
            return Optional.of(new DartDataSnapshot(stockName, annual, recentQuarterly));
        } catch (RuntimeException e) {
            log.warn("시뮬레이션 근거 DART 조회 실패 - stockCode: {}, 사유: {}", stockCode, e.getMessage());
            return Optional.empty();
        }
    }

    private Map<String, NaverNewsSearchResponse> fetchNewsData(Map<String, String> stocks) {
        Map<String, NaverNewsSearchResponse> result = new LinkedHashMap<>();
        try (var virtualExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            Map<String, CompletableFuture<Optional<NaverNewsSearchResponse>>> futures = new LinkedHashMap<>();
            stocks.forEach((stockCode, stockName) -> futures.put(stockCode, CompletableFuture.supplyAsync(() -> {
                try {
                    return Optional.ofNullable(naverNewsApiClient.search(new NaverNewsSearchRequest(stockName, null, null)));
                } catch (RuntimeException e) {
                    log.warn("시뮬레이션 근거 뉴스 조회 실패 - stockCode: {}, 사유: {}", stockCode, e.getMessage());
                    return Optional.<NaverNewsSearchResponse>empty();
                }
            }, virtualExecutor)));
            futures.forEach((stockCode, future) -> future.join().ifPresent(news -> result.put(stockCode, news)));
        }
        return result;
    }

    // ===== ⑧ 설명 생성 =====

    private SimulationExplanation requestExplanation(InvestmentProfile profile, GoalExtraction goal, long monthlyContribution,
                                                     long holdingsAmount, long cashAmount,
                                                     PortfolioProjectionDto current, PortfolioProjectionDto rebalanced,
                                                     Integer shortenedMonths, Map<String, String> rationaleStocks,
                                                     Map<String, DartDataSnapshot> dartData,
                                                     Map<String, NaverNewsSearchResponse> newsData) {
        StringBuilder prompt = new StringBuilder();
        prompt.append(describeProfile(profile)).append("\n\n");
        prompt.append("[목표]\n").append(describeGoal(goal)).append("\n\n");
        prompt.append("[시작 금액] 보유종목 %d원 + 예수금 %d원 = %d원, 월 추가 납입 %d원\n"
                .formatted(holdingsAmount, cashAmount, holdingsAmount + cashAmount, monthlyContribution));
        prompt.append("[성장률 계산 방식] 종목별 최근 3년 월봉 복리(기하평균) 월 수익률에서 30% 할인(음수면 그대로), "
                + "종목당 월 5% 상한, 예수금은 0%, 비중으로 가중평균\n\n");
        prompt.append("[현재 보유 유지]\n").append(describeProjection(current)).append('\n');
        prompt.append("[리밸런싱]\n").append(describeProjection(rebalanced));
        if (!rebalanced.excludedStockNames().isEmpty()) {
            prompt.append("- 시세 이력 12개월 미만이라 제외하고 예수금으로 돌린 종목: ")
                    .append(String.join(", ", rebalanced.excludedStockNames())).append('\n');
        }
        prompt.append('\n').append("[단축 개월 수] ")
                .append(shortenedMonths != null ? shortenedMonths + "개월(음수면 늦어짐)" : "비교 불가(한쪽 이상이 30년 안에 도달하지 못함)")
                .append("\n\n");

        prompt.append("[종목별 근거 데이터] (비중 변화가 큰 종목)\n");
        rationaleStocks.forEach((stockCode, stockName) -> {
            prompt.append("■ ").append(stockName).append('(').append(stockCode).append(")\n");
            DartDataSnapshot dart = dartData.get(stockCode);
            if (dart != null) {
                prompt.append("- 재무(연간): ").append(describeFinancials(dart.annual())).append('\n');
                prompt.append("- 재무(최근 분기): ").append(describeFinancials(dart.recentQuarterly())).append('\n');
            } else {
                prompt.append("- 재무 데이터 없음\n");
            }
            NaverNewsSearchResponse news = newsData.get(stockCode);
            if (news != null && news.results() != null && !news.results().isEmpty()) {
                news.results().stream().limit(NEWS_ITEMS_PER_STOCK).forEach(article ->
                        prompt.append("- 뉴스: ").append(article.title()).append(" — ").append(article.description()).append('\n'));
            } else {
                prompt.append("- 관련 뉴스 없음\n");
            }
        });

        GeminiResponse response = geminiApiClient.generate(new GeminiRequest(EXPLANATION_INSTRUCTION, prompt.toString(),
                List.of(), List.of(), List.of(), GeminiRequest.GeminiModel.ANSWER));
        try {
            SimulationExplanation explanation = objectMapper.readValue(stripJsonFence(response.content()), SimulationExplanation.class);
            if (explanation.rebalanceReason() == null || explanation.timeReductionExplanation() == null) {
                throw new CustomException(ErrorCode.SCENARIO_DATA_PARSE_ERROR);
            }
            return explanation;
        } catch (JacksonException e) {
            throw new CustomException(ErrorCode.SCENARIO_DATA_PARSE_ERROR, e);
        }
    }

    private String describeProfile(InvestmentProfile profile) {
        return "[투자성향] 투자성향 %d단계(1=안정형 ~ 5=공격투자형), 자금성향 %s, 투자 수준 %s".formatted(
                profile.getInvestmentTendency(),
                switch (profile.getFundTendency()) {
                    case 1 -> "수익추구형";
                    case 2 -> "자유소비형";
                    case 3 -> "목표달성형";
                    default -> "미분류";
                },
                profile.getInvestmentLevel());
    }

    private String describeGoal(GoalExtraction goal) {
        return goal.periodMonths() != null
                ? "%d원, 기한 %d개월".formatted(goal.targetAmount(), goal.periodMonths())
                : "%d원, 기한 없음".formatted(goal.targetAmount());
    }

    private String describeProjection(PortfolioProjectionDto projection) {
        StringBuilder text = new StringBuilder();
        projection.allocations().forEach(allocation -> text.append("- %s(%s) %.2f%%, 월 성장률 %.3f%%\n"
                .formatted(allocation.stockName(), allocation.stockCode(), allocation.weight(), allocation.monthlyGrowthRate() * 100)));
        text.append("- 예수금 %.2f%%\n".formatted(projection.cashWeight()));
        text.append("- 포트폴리오 월 성장률 %.3f%%\n".formatted(projection.monthlyGrowthRate() * 100));
        text.append("- 목표 도달: ").append(projection.reachMonths() != null
                ? "%d개월 뒤(%d년 %d월)".formatted(projection.reachMonths(), projection.reachDate().getYear(), projection.reachDate().getMonthValue())
                : "30년 안에 도달하지 못함").append('\n');
        if (projection.achievableWithinPeriod() != null) {
            text.append("- 기한 안 달성: ").append(projection.achievableWithinPeriod() ? "가능" : "불가").append('\n');
        }
        return text.toString();
    }

    private String describeFinancials(DartFinancialResponse financials) {
        if (financials == null) {
            return "없음";
        }
        return "%d년 매출 %s, 영업이익 %s, 순이익 %s, 자산 %s, 부채 %s, 자본 %s".formatted(financials.bizYear(),
                financials.revenue(), financials.operatingProfit(), financials.netIncome(),
                financials.totalAssets(), financials.totalLiabilities(), financials.totalEquity());
    }

    // ===== 공통 =====

    private PortfolioProjectionDto buildProjection(double monthlyRate, double cashWeight, List<PortfolioAllocationDto> allocations,
                                                   List<String> excludedStockNames, List<ScenarioPointDto> fullCurve,
                                                   int displayMonths, Integer reachMonths, Integer periodMonths) {
        LocalDate reachDate = reachMonths != null ? fullCurve.get(reachMonths).date() : null;
        Boolean achievableWithinPeriod = periodMonths == null ? null : reachMonths != null && reachMonths <= periodMonths;
        return new PortfolioProjectionDto(monthlyRate, cashWeight, allocations, excludedStockNames,
                List.copyOf(fullCurve.subList(0, displayMonths + 1)), reachMonths, reachDate, achievableWithinPeriod);
    }

    private static double round2(double value) {
        return Math.round(value * 100) / 100.0;
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

    private <T> T readJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JacksonException e) {
            throw new CustomException(ErrorCode.SCENARIO_DATA_PARSE_ERROR, e);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new CustomException(ErrorCode.SCENARIO_DATA_PARSE_ERROR, e);
        }
    }

    // ===== Gemini 응답 파싱 전용 내부 타입(NAMING.md에 올리지 않는 구현 세부사항) =====
    // ignoreUnknown은 Gemini가 지침을 어기고 여분의 필드를 더 얹어 보내도 파싱이 실패하지 않도록 하는 방어다.

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GoalExtraction(Long targetAmount, Integer periodMonths) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RebalanceSuggestion(double cashWeight, List<RebalanceItem> allocations) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RebalanceItem(String stockCode, double weight) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SimulationExplanation(String rebalanceReason, String timeReductionExplanation) {
    }

    // simulations.dart_data 컬럼에 종목코드별로 그대로 저장할 원본 스냅샷.
    private record DartDataSnapshot(
            String stockName,
            DartFinancialResponse annual,
            DartFinancialResponse recentQuarterly
    ) {
    }
}
