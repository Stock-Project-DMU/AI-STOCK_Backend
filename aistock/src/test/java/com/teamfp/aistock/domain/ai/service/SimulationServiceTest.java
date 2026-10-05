package com.teamfp.aistock.domain.ai.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import com.teamfp.aistock.domain.account.dto.response.AccountInfoResponse;
import com.teamfp.aistock.domain.account.entity.AccountStatus;
import com.teamfp.aistock.domain.account.service.AccountService;
import com.teamfp.aistock.domain.ai.dto.PendingSimulationDto;
import com.teamfp.aistock.domain.ai.dto.PortfolioAllocationDto;
import com.teamfp.aistock.domain.ai.dto.request.SaveSimulationRequest;
import com.teamfp.aistock.domain.ai.dto.request.SimulationRequest;
import com.teamfp.aistock.domain.ai.dto.response.SimulationResponse;
import com.teamfp.aistock.domain.ai.entity.Simulation;
import com.teamfp.aistock.domain.ai.repository.SimulationRepository;
import com.teamfp.aistock.domain.notification.entity.NotificationType;
import com.teamfp.aistock.domain.notification.service.NotificationService;
import com.teamfp.aistock.domain.order.dto.HoldingValuationDto;
import com.teamfp.aistock.domain.order.service.HoldingValuationService;
import com.teamfp.aistock.domain.stock.service.MarketQueryService;
import com.teamfp.aistock.domain.user.entity.InvestmentLevel;
import com.teamfp.aistock.domain.user.entity.InvestmentProfile;
import com.teamfp.aistock.domain.user.entity.Role;
import com.teamfp.aistock.domain.user.entity.User;
import com.teamfp.aistock.domain.user.repository.InvestmentProfileRepository;
import com.teamfp.aistock.domain.user.repository.UserRepository;
import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;
import com.teamfp.aistock.global.redis.RedisPendingSimulationService;
import com.teamfp.aistock.global.redis.RedisRateLimiterService;
import com.teamfp.aistock.infra.dart.DartApiClient;
import com.teamfp.aistock.infra.gemini.GeminiApiClient;
import com.teamfp.aistock.infra.gemini.dto.GeminiRequest;
import com.teamfp.aistock.infra.gemini.dto.GeminiResponse;
import com.teamfp.aistock.infra.marketdata.dto.HistoricalPriceDto;
import com.teamfp.aistock.infra.marketdata.dto.RankingItemDto;
import com.teamfp.aistock.infra.naver.NaverNewsApiClient;
import com.teamfp.aistock.infra.naver.dto.NaverNewsSearchResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * feature/goal-simulation-v2 — SimulationService 단위 테스트. 외부 API(Gemini/DART/네이버/시세)는
 * 모두 mock이며, Gemini 응답은 호출 순서(① 목표 해석 ② 리밸런싱 추천 ③ 설명)대로 돌려준다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SimulationServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long ACCOUNT_ID = 10L;

    private static final String GOAL_JSON = "{\"targetAmount\": 20000000, \"periodMonths\": 36}";
    private static final String REBALANCE_JSON =
            "{\"cashWeight\": 20, \"allocations\": [{\"stockCode\": \"005930\", \"weight\": 40}, {\"stockCode\": \"000660\", \"weight\": 40}]}";
    private static final String EXPLANATION_JSON =
            "{\"rebalanceReason\": \"성향에 맞춰 조정했습니다.\", \"timeReductionExplanation\": \"도달 시점이 앞당겨집니다.\"}";

    @Mock private SimulationRepository simulationRepository;
    @Mock private UserRepository userRepository;
    @Mock private InvestmentProfileRepository investmentProfileRepository;
    @Mock private AccountService accountService;
    @Mock private HoldingValuationService holdingValuationService;
    @Mock private MarketQueryService marketQueryService;
    @Mock private DartApiClient dartApiClient;
    @Mock private NaverNewsApiClient naverNewsApiClient;
    @Mock private GeminiApiClient geminiApiClient;
    @Mock private RedisRateLimiterService rateLimiterService;
    @Mock private RedisPendingSimulationService pendingSimulationService;
    @Mock private NotificationService notificationService;

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private SimulationService simulationService;

    private final User user = User.builder().loginId("tester").name("테스터").role(Role.USER).isActive(true).build();

    @BeforeEach
    void setUp() {
        simulationService = new SimulationService(simulationRepository, userRepository, investmentProfileRepository,
                accountService, holdingValuationService, marketQueryService, dartApiClient, naverNewsApiClient,
                geminiApiClient, rateLimiterService, pendingSimulationService, notificationService, objectMapper);

        when(rateLimiterService.isAllowed(USER_ID)).thenReturn(true);
        when(investmentProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(InvestmentProfile.builder()
                .user(user).investmentTendency(3).fundTendency(3).investmentLevel(InvestmentLevel.INTERMEDIATE).build()));
        when(accountService.getMyAccounts(USER_ID)).thenReturn(List.of(new AccountInfoResponse(
                ACCOUNT_ID, "계좌A", "1234", 5_000_000, 0, 10_000_000, 0, 3, false, 100_000_000L, 0L, new java.math.BigDecimal("0.50"), 0L, AccountStatus.ACTIVE)));
        // 보유: 035420 100주 × 50,000원 = 5,000,000원 → 시작 금액 10,000,000원(보유 50%, 예수금 50%)
        when(holdingValuationService.getHoldingValuations(ACCOUNT_ID)).thenReturn(List.of(
                new HoldingValuationDto(ACCOUNT_ID, "035420", "NAVER", 100, 40_000, 50_000)));
        when(marketQueryService.getRankings("market-cap", true)).thenReturn(List.of(
                ranking("005930", "삼성전자"), ranking("000660", "SK하이닉스"), ranking("035420", "NAVER")));
        // 기본 시세 이력: 모든 종목 월 +1%(37개 종가)
        when(marketQueryService.getChartHistory(anyString(), eq(3), eq(37))).thenReturn(monthlyHistory(37, 0.01));
        when(dartApiClient.resolveCorpCodeByStockCode(anyString())).thenReturn(Optional.empty());
        when(naverNewsApiClient.search(any())).thenReturn(new NaverNewsSearchResponse(List.of()));
    }

    private static RankingItemDto ranking(String stockCode, String stockName) {
        return RankingItemDto.builder().stockCode(stockCode).stockName(stockName).build();
    }

    // 최신순 월봉 종가 count개. 과거 → 현재로 매달 monthlyReturn씩 오른 시계열.
    private static List<HistoricalPriceDto> monthlyHistory(int count, double monthlyReturn) {
        List<HistoricalPriceDto> prices = new ArrayList<>();
        double close = 100_000;
        for (int i = 0; i < count; i++) {
            prices.add(HistoricalPriceDto.builder().close(Math.round(close)).build());
            close = close / (1 + monthlyReturn);
        }
        return prices;
    }

    private static GeminiResponse gemini(String content) {
        return new GeminiResponse(content, 0, List.of());
    }

    @Nested
    @DisplayName("시뮬레이션 실행")
    class RunSimulation {

        @Test
        @DisplayName("성공 - 왼쪽(현재 보유)·오른쪽(리밸런싱) 결과를 계산하고 Redis에 30분 보관한다")
        void success() {
            when(geminiApiClient.generate(any())).thenReturn(gemini(GOAL_JSON), gemini(REBALANCE_JSON), gemini(EXPLANATION_JSON));

            SimulationResponse response = simulationService.runSimulation(USER_ID, new SimulationRequest("3년 안에 2천만원", 100_000));

            assertThat(response.simulationId()).isNull();
            assertThat(response.pendingSimulationId()).isNotBlank();
            assertThat(response.targetAmount()).isEqualTo(20_000_000);
            assertThat(response.periodMonths()).isEqualTo(36);
            assertThat(response.startAmount()).isEqualTo(10_000_000);
            assertThat(response.holdingsAmount()).isEqualTo(5_000_000);
            assertThat(response.cashAmount()).isEqualTo(5_000_000);

            // 현재: NAVER 50% + 예수금 50%, 종목 월 성장률 = 1% × 0.7 = 0.7% → 포트폴리오 0.35%
            assertThat(response.current().cashWeight()).isEqualTo(50.0);
            assertThat(response.current().allocations()).extracting(PortfolioAllocationDto::weight).containsExactly(50.0);
            assertThat(response.current().monthlyGrowthRate()).isCloseTo(0.0035, org.assertj.core.api.Assertions.within(1e-6));
            // 리밸런싱: 주식 80% + 예수금 20% → 0.56%
            assertThat(response.rebalanced().cashWeight()).isEqualTo(20.0);
            assertThat(response.rebalanced().monthlyGrowthRate()).isCloseTo(0.0056, org.assertj.core.api.Assertions.within(1e-6));
            assertThat(response.rebalanced().allocations()).extracting(PortfolioAllocationDto::stockName)
                    .containsExactly("삼성전자", "SK하이닉스");

            assertThat(response.current().reachMonths()).isNotNull();
            assertThat(response.rebalanced().reachMonths()).isLessThan(response.current().reachMonths());
            assertThat(response.shortenedMonths())
                    .isEqualTo(response.current().reachMonths() - response.rebalanced().reachMonths());
            assertThat(response.current().points()).hasSameSizeAs(response.rebalanced().points());
            assertThat(response.rebalanceReason()).isEqualTo("성향에 맞춰 조정했습니다.");

            verify(rateLimiterService, times(1)).increment(USER_ID);
            verify(pendingSimulationService).savePending(eq(USER_ID), eq(response.pendingSimulationId()), anyString());
        }

        @Test
        @DisplayName("호출 한도 초과면 Gemini를 부르지 않고 GEMINI_RATE_LIMIT_EXCEEDED")
        void rateLimited() {
            when(rateLimiterService.isAllowed(USER_ID)).thenReturn(false);

            assertThatThrownBy(() -> simulationService.runSimulation(USER_ID, new SimulationRequest("1억", 0)))
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.GEMINI_RATE_LIMIT_EXCEEDED);
            verify(geminiApiClient, never()).generate(any());
        }

        @Test
        @DisplayName("투자성향 미설정이면 INVESTMENT_PROFILE_REQUIRED")
        void profileRequired() {
            when(investmentProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> simulationService.runSimulation(USER_ID, new SimulationRequest("1억", 0)))
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVESTMENT_PROFILE_REQUIRED);
            verify(geminiApiClient, never()).generate(any());
            verify(rateLimiterService, never()).increment(USER_ID);
        }

        @Test
        @DisplayName("목표 금액을 추출하지 못하면 GOAL_TEXT_PARSE_FAILED(재입력 안내는 고정 문구)")
        void goalParseFailed() {
            when(geminiApiClient.generate(any())).thenReturn(gemini("{\"targetAmount\": null, \"periodMonths\": null}"));

            assertThatThrownBy(() -> simulationService.runSimulation(USER_ID, new SimulationRequest("부자 되기", 0)))
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.GOAL_TEXT_PARSE_FAILED);
            verify(geminiApiClient, times(1)).generate(any());
            // 목표 문장을 고쳐 다시 시도할 실패라 호출 한도를 차감하지 않는다.
            verify(rateLimiterService, never()).increment(USER_ID);
        }

        @Test
        @DisplayName("목표 해석 응답이 JSON이 아니어도 GOAL_TEXT_PARSE_FAILED")
        void goalNotJson() {
            when(geminiApiClient.generate(any())).thenReturn(gemini("금액을 알 수 없어요"));

            assertThatThrownBy(() -> simulationService.runSimulation(USER_ID, new SimulationRequest("부자 되기", 0)))
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.GOAL_TEXT_PARSE_FAILED);
        }

        @Test
        @DisplayName("기한이 30년을 넘으면 GOAL_PERIOD_OUT_OF_RANGE")
        void periodOutOfRange() {
            when(geminiApiClient.generate(any())).thenReturn(gemini("{\"targetAmount\": 100000000, \"periodMonths\": 480}"));

            assertThatThrownBy(() -> simulationService.runSimulation(USER_ID, new SimulationRequest("40년 안에 1억", 0)))
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.GOAL_PERIOD_OUT_OF_RANGE);
            verify(rateLimiterService, never()).increment(USER_ID);
        }

        @Test
        @DisplayName("보유종목이 없으면 예수금 100%로 시작하고 리밸런싱은 신규 종목만 추천받는다")
        void noHoldings() {
            when(holdingValuationService.getHoldingValuations(ACCOUNT_ID)).thenReturn(List.of());
            when(geminiApiClient.generate(any())).thenReturn(gemini(GOAL_JSON), gemini(REBALANCE_JSON), gemini(EXPLANATION_JSON));

            SimulationResponse response = simulationService.runSimulation(USER_ID, new SimulationRequest("3년 안에 2천만원", 0));

            assertThat(response.startAmount()).isEqualTo(5_000_000);
            assertThat(response.current().allocations()).isEmpty();
            assertThat(response.current().cashWeight()).isEqualTo(100.0);
            assertThat(response.current().monthlyGrowthRate()).isZero();
            assertThat(response.rebalanced().allocations()).hasSize(2);
        }

        @Test
        @DisplayName("리밸런싱 추천이 규칙을 어기면 위반 사유를 붙여 1회 다시 요청한다")
        void retriesRebalanceOnce() {
            String invalid = "{\"cashWeight\": 0, \"allocations\": [{\"stockCode\": \"005930\", \"weight\": 100}]}";
            when(geminiApiClient.generate(any())).thenReturn(gemini(GOAL_JSON), gemini(invalid), gemini(REBALANCE_JSON),
                    gemini(EXPLANATION_JSON));

            SimulationResponse response = simulationService.runSimulation(USER_ID, new SimulationRequest("3년 안에 2천만원", 0));

            ArgumentCaptor<GeminiRequest> captor = ArgumentCaptor.forClass(GeminiRequest.class);
            verify(geminiApiClient, times(4)).generate(captor.capture());
            assertThat(captor.getAllValues().get(2).prompt()).contains("[이전 답변의 문제]");
            assertThat(response.rebalanced().allocations()).hasSize(2);
        }

        @Test
        @DisplayName("재요청까지 규칙을 어기면 REBALANCE_SUGGESTION_INVALID")
        void rebalanceInvalidTwice() {
            String invalid = "{\"cashWeight\": 0, \"allocations\": [{\"stockCode\": \"999999\", \"weight\": 100}]}";
            when(geminiApiClient.generate(any())).thenReturn(gemini(GOAL_JSON), gemini(invalid), gemini(invalid));

            assertThatThrownBy(() -> simulationService.runSimulation(USER_ID, new SimulationRequest("3년 안에 2천만원", 0)))
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.REBALANCE_SUGGESTION_INVALID);
            // 목표 해석이 끝난 뒤의 실패는 Gemini를 이미 여러 번 불렀으므로 1회로 센다.
            verify(rateLimiterService, times(1)).increment(USER_ID);
        }

        @Test
        @DisplayName("시세 이력이 12개월 미만인 추천 종목은 빼고 그 비중을 예수금으로 옮긴다")
        void excludesShortHistory() {
            when(marketQueryService.getChartHistory(eq("000660"), anyInt(), anyInt())).thenReturn(monthlyHistory(6, 0.05));
            when(geminiApiClient.generate(any())).thenReturn(gemini(GOAL_JSON), gemini(REBALANCE_JSON), gemini(EXPLANATION_JSON));

            SimulationResponse response = simulationService.runSimulation(USER_ID, new SimulationRequest("3년 안에 2천만원", 0));

            assertThat(response.rebalanced().allocations()).extracting(PortfolioAllocationDto::stockCode).containsExactly("005930");
            assertThat(response.rebalanced().cashWeight()).isEqualTo(60.0);
            assertThat(response.rebalanced().excludedStockNames()).containsExactly("SK하이닉스");
        }

        @Test
        @DisplayName("30년 안에 도달하지 못하면 도달 개월·단축 개월이 null이고 곡선은 30년 전체다")
        void unreachable() {
            when(marketQueryService.getChartHistory(anyString(), eq(3), eq(37))).thenReturn(monthlyHistory(37, 0.0));
            when(geminiApiClient.generate(any())).thenReturn(gemini("{\"targetAmount\": 999000000000, \"periodMonths\": null}"),
                    gemini(REBALANCE_JSON), gemini(EXPLANATION_JSON));

            SimulationResponse response = simulationService.runSimulation(USER_ID, new SimulationRequest("1조 만들기", 0));

            assertThat(response.current().reachMonths()).isNull();
            assertThat(response.rebalanced().reachMonths()).isNull();
            assertThat(response.shortenedMonths()).isNull();
            assertThat(response.current().achievableWithinPeriod()).isNull();
            assertThat(response.current().points()).hasSize(361);
        }
    }

    @Nested
    @DisplayName("시뮬레이션 저장")
    class SaveSimulation {

        @Test
        @DisplayName("Redis 보관 결과를 DB에 저장하고 알림을 보낸다")
        void success() {
            when(geminiApiClient.generate(any())).thenReturn(gemini(GOAL_JSON), gemini(REBALANCE_JSON), gemini(EXPLANATION_JSON));
            SimulationResponse run = simulationService.runSimulation(USER_ID, new SimulationRequest("3년 안에 2천만원", 100_000));
            ArgumentCaptor<String> pendingJson = ArgumentCaptor.forClass(String.class);
            verify(pendingSimulationService).savePending(eq(USER_ID), anyString(), pendingJson.capture());
            when(pendingSimulationService.claimPending(USER_ID, run.pendingSimulationId())).thenReturn(Optional.of(pendingJson.getValue()));
            when(userRepository.getReferenceById(USER_ID)).thenReturn(user);

            SimulationResponse saved = simulationService.saveSimulation(USER_ID, new SaveSimulationRequest(run.pendingSimulationId()));

            ArgumentCaptor<Simulation> captor = ArgumentCaptor.forClass(Simulation.class);
            verify(simulationRepository).save(captor.capture());
            Simulation simulation = captor.getValue();
            assertThat(simulation.getGoalText()).isEqualTo("3년 안에 2천만원");
            assertThat(simulation.getTargetAmount()).isEqualTo(20_000_000);
            assertThat(simulation.getRebalancedReachDate()).isEqualTo(run.rebalanced().reachDate());
            assertThat(saved.pendingSimulationId()).isNull();
            assertThat(saved.rebalanced().points()).hasSameSizeAs(run.rebalanced().points());
            verify(notificationService).notify(eq(USER_ID), eq(NotificationType.SIMULATION), anyString(), anyString());
        }

        @Test
        @DisplayName("보관 시간이 지났으면 SIMULATION_EXPIRED")
        void expired() {
            when(pendingSimulationService.claimPending(USER_ID, "old")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> simulationService.saveSimulation(USER_ID, new SaveSimulationRequest("old")))
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.SIMULATION_EXPIRED);
            verify(simulationRepository, never()).save(any());
        }

        @Test
        @DisplayName("이미 저장한 결과를 다시 저장하면 SIMULATION_EXPIRED가 아니라 SIMULATION_ALREADY_SAVED")
        void alreadySaved() {
            when(pendingSimulationService.claimPending(USER_ID, "done"))
                    .thenReturn(Optional.of(RedisPendingSimulationService.SAVED_MARKER));

            assertThatThrownBy(() -> simulationService.saveSimulation(USER_ID, new SaveSimulationRequest("done")))
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.SIMULATION_ALREADY_SAVED);
            verify(simulationRepository, never()).save(any());
            verify(notificationService, never()).notify(anyLong(), any(), anyString(), anyString());
        }

        @Test
        @DisplayName("보관 JSON은 PendingSimulationDto로 역직렬화된다")
        void pendingJsonRoundTrip() {
            when(geminiApiClient.generate(any())).thenReturn(gemini(GOAL_JSON), gemini(REBALANCE_JSON), gemini(EXPLANATION_JSON));
            simulationService.runSimulation(USER_ID, new SimulationRequest("3년 안에 2천만원", 0));
            ArgumentCaptor<String> pendingJson = ArgumentCaptor.forClass(String.class);
            verify(pendingSimulationService).savePending(anyLong(), anyString(), pendingJson.capture());

            PendingSimulationDto pending = objectMapper.readValue(pendingJson.getValue(), PendingSimulationDto.class);

            assertThat(pending.result().current().reachDate()).isNotNull();
        }
    }
}
