package com.teamfp.aistock.domain.ai.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.teamfp.aistock.domain.account.dto.response.AccountInfoResponse;
import com.teamfp.aistock.domain.account.service.AccountService;
import com.teamfp.aistock.domain.ai.dto.request.AiChatRequest;
import com.teamfp.aistock.domain.ai.dto.response.AiChatResponse;
import com.teamfp.aistock.domain.ai.dto.response.AiPlanningSessionResponse;
import com.teamfp.aistock.domain.ai.entity.AiPlanningMessage;
import com.teamfp.aistock.domain.ai.entity.AiPlanningSession;
import com.teamfp.aistock.domain.ai.entity.MessageRole;
import com.teamfp.aistock.domain.ai.repository.AiPlanningMessageRepository;
import com.teamfp.aistock.domain.ai.repository.AiPlanningSessionRepository;
import com.teamfp.aistock.domain.order.dto.HoldingValuationDto;
import com.teamfp.aistock.domain.order.service.HoldingValuationService;
import com.teamfp.aistock.domain.user.entity.InvestmentProfile;
import com.teamfp.aistock.domain.user.entity.User;
import com.teamfp.aistock.domain.user.repository.InvestmentProfileRepository;
import com.teamfp.aistock.domain.user.repository.UserRepository;
import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;
import com.teamfp.aistock.global.redis.RedisAiToolCacheService;
import com.teamfp.aistock.global.redis.RedisRateLimiterService;
import com.teamfp.aistock.global.util.DateUtil;
import com.teamfp.aistock.infra.dart.DartApiClient;
import com.teamfp.aistock.infra.dart.dto.DartFinancialRequest;
import com.teamfp.aistock.infra.dart.dto.DartFinancialResponse;
import com.teamfp.aistock.infra.gemini.GeminiApiClient;
import com.teamfp.aistock.infra.gemini.dto.GeminiRequest;
import com.teamfp.aistock.infra.gemini.dto.GeminiRequest.HistoryTurn;
import com.teamfp.aistock.infra.gemini.dto.GeminiResponse;
import com.teamfp.aistock.infra.ls.LsEtcApiClient;
import com.teamfp.aistock.infra.ls.LsEtfApiClient;
import com.teamfp.aistock.infra.ls.LsHighItemApiClient;
import com.teamfp.aistock.infra.ls.LsIndustryApiClient;
import com.teamfp.aistock.infra.ls.LsInvestInfoApiClient;
import com.teamfp.aistock.infra.ls.LsInvestorApiClient;
import com.teamfp.aistock.infra.ls.LsInvestorTrendApiClient;
import com.teamfp.aistock.infra.ls.LsMarketDataApiClient;
import com.teamfp.aistock.infra.ls.LsProgramApiClient;
import com.teamfp.aistock.infra.ls.LsSectorApiClient;
import com.teamfp.aistock.infra.ls.dto.LsCallAuctionPriceDto;
import com.teamfp.aistock.infra.ls.dto.LsCurrentPriceDetailDto;
import com.teamfp.aistock.infra.ls.dto.LsEtfConstituentDto;
import com.teamfp.aistock.infra.ls.dto.LsExpectedIndexDto;
import com.teamfp.aistock.infra.ls.dto.LsFinancialRankingDto;
import com.teamfp.aistock.infra.ls.dto.LsForeignInstitutionalTrendDto;
import com.teamfp.aistock.infra.ls.dto.LsHistoricalPriceDto;
import com.teamfp.aistock.infra.ls.dto.LsIndustryPriceDto;
import com.teamfp.aistock.infra.ls.dto.LsIndustryTrendDto;
import com.teamfp.aistock.infra.ls.dto.LsInvestmentOpinionDto;
import com.teamfp.aistock.infra.ls.dto.LsInvestorTypeSummaryDto;
import com.teamfp.aistock.infra.ls.dto.LsMarketInvestorComparisonDto;
import com.teamfp.aistock.infra.ls.dto.LsMarketLiquidityDto;
import com.teamfp.aistock.infra.ls.dto.LsMultiStockPriceDto;
import com.teamfp.aistock.infra.ls.dto.LsNewListingDto;
import com.teamfp.aistock.infra.ls.dto.LsOverseasIndexDto;
import com.teamfp.aistock.infra.ls.dto.LsPivotLevelDto;
import com.teamfp.aistock.infra.ls.dto.LsProgramTradingRankDto;
import com.teamfp.aistock.infra.ls.dto.LsProgramTradingSnapshotDto;
import com.teamfp.aistock.infra.ls.dto.LsRankingItemDto;
import com.teamfp.aistock.infra.ls.dto.LsShareholderMeetingDto;
import com.teamfp.aistock.infra.ls.dto.LsShortSellingTrendDto;
import com.teamfp.aistock.infra.ls.dto.LsStockCreditInfoDto;
import com.teamfp.aistock.infra.ls.dto.LsStockMasterInfoDto;
import com.teamfp.aistock.infra.ls.dto.LsStockRiskFlagDto;
import com.teamfp.aistock.infra.ls.dto.LsThemeConstituentDto;
import com.teamfp.aistock.infra.ls.dto.LsThemeDto;
import com.teamfp.aistock.infra.naver.NaverNewsApiClient;
import com.teamfp.aistock.infra.naver.dto.NaverNewsSearchRequest;
import com.teamfp.aistock.infra.naver.dto.NaverNewsSearchResponse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * AI 재무설계 상담. 사용자의 투자정보(DB) + 보유종목을 고정 컨텍스트로 Gemini에 전달하고,
 * 뉴스 검색(Tavily)·재무제표 조회(DART)가 필요한지·어떤 회사를 대상으로 할지는 Gemini의 함수
 * 호출(Function Calling)로 판단시킨다. 사용자 문장을 그대로 검색어/회사명으로 쓰지 않고 Gemini가
 * 이해한 뒤 스스로 판단하게 해서("판단 로직"), 애매한 종목은 조회 대신 되묻고 엉뚱한 종목으로
 * 조회되는 문제를 막는다. 도구 실행 결과는 다시 Gemini에 되돌려줘, 항목별 보고서가 아니라
 * 고객을 계속 챙기는 재무설계사가 대화하듯 자연스러운 문단으로 정리시킨 뒤("가공 로직") 대화
 * 내용을 세션에 이어서 저장한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiPlanningService {
    private final PlanningPreferencesService planningPreferencesService;

    // DART 사업보고서는 회계연도가 끝난 다음 해에 공시되므로, 안정적으로 존재하는 "작년" 실적을 조회한다.
    private static final int DART_YEAR_OFFSET = 1;
    // Gemini에 매번 전체 대화를 보내면 토큰 비용이 계속 늘어나므로 최근 N개만 히스토리로 보낸다.
    // 세션 하나 = 하나의 큰 주제(예: 삼성그룹 전체, SK그룹 전체)로 쓰는 게 실제 사용 패턴이라는
    // 걸 확인한 뒤(2026-08-06), "그 세션 안에서 나눈 대화는 어느 정도 기억해야 한다"는 요구에
    // 맞춰 기존 20개(대화 10턴)에서 60개(대화 30턴)로 늘렸다 — 짧은 채팅 메시지 60개는 값싼
    // 모델(gemini-3.1-flash-lite, 입력 $0.25/100만 토큰) 기준으로도 매 턴 비용 증가가 미미하다.
    // 세션이 이 한도를 넘어설 만큼 아주 길어지면(예: 60턴 이상) 그보다 오래된 대화는 여전히
    // 안 보이게 되는데, 그 경우엔 오래된 턴을 요약해서 압축 포함시키는 방식이 정석이지만
    // 아직 구현하지 않았다(다음 단계 후보 — schema.sql/redis-logic.md에도 아직 반영 안 함).
    private static final int MAX_HISTORY_MESSAGES = 60;
    // 세션 제목은 첫 메시지 앞부분을 잘라 자동 생성한다 (ai_planning_sessions.title varchar(100)
    // 여유를 두고 30자로 제한 — 목록 화면에서 한 줄로 보여주기 좋은 길이).
    private static final int TITLE_MAX_LENGTH = 30;

    // Gemini가 판단해서 부를 수 있는 도구 이름들. executeTool()의 분기 기준이자 functionCall
    // 응답에 실려오는 name과 정확히 일치해야 한다.
    private static final String NEWS_SEARCH_TOOL_NAME = "search_securities_news";
    // 2026-08-11 추가 — sendMessage()가 세션 첫 턴에 코드로 직접 붙이는 인사말. SYSTEM_PREAMBLE의
    // [첫 인사] 참고 — 모델은 이 인사를 스스로 넣지 않도록 지시돼 있다.
    private static final String FIRST_TURN_GREETING = "안녕하세요! AI 재무설계사 STOCK입니다.\n\n";

    private static final String FINANCIALS_TOOL_NAME = "get_financial_statements";
    private static final String CAPITAL_CHANGE_TOOL_NAME = "get_capital_change_info";
    private static final String OWNERSHIP_TOOL_NAME = "get_ownership_info";
    private static final String DISCLOSURE_TOOL_NAME = "get_disclosure_info";
    private static final String CURRENT_PRICE_TOOL_NAME = "get_current_price";
    private static final String FOREIGN_INSTITUTIONAL_TREND_TOOL_NAME = "get_foreign_institutional_trend";
    private static final String INVESTMENT_OPINION_TOOL_NAME = "get_investment_opinion";
    private static final String SHAREHOLDER_MEETING_TOOL_NAME = "get_shareholder_meeting_schedule";
    // 2026-08-11 추가 — LS API 213개 전수조사·2차 필터링 결과 확정된 도구들. 원본 TR은
    // 45개였지만, 도구 하나당 TR 하나씩 그대로 노출하면 Gemini에게 매 판단마다 건네는 도구
    // 목록이 50개를 훌쩍 넘어(기존 9개+45개=54개) 판단 정확도·프롬프트 비용 모두 나빠진다.
    // DISCLOSURE_TOOL/CAPITAL_CHANGE_TOOL이 이미 쓰던 "여러 TR을 종류 파라미터 하나로 묶는"
    // 패턴을 그대로 적용해 도구 개수를 최대한 압축한다.
    private static final String MARKET_RANKING_TOOL_NAME = "get_market_ranking";
    private static final String THEME_INFO_TOOL_NAME = "get_theme_info";
    private static final String FINANCIAL_RANKING_TOOL_NAME = "get_financial_ranking";
    private static final String OVERSEAS_INDEX_TOOL_NAME = "get_overseas_index";
    private static final String MARKET_LIQUIDITY_TOOL_NAME = "get_market_liquidity_trend";
    private static final String TECHNICAL_SIGNAL_TOOL_NAME = "get_stock_technical_signal";
    private static final String HISTORICAL_PRICE_TOOL_NAME = "get_historical_price";
    private static final String MULTI_STOCK_PRICE_TOOL_NAME = "get_multi_stock_price";
    private static final String RISK_FLAG_TOOL_NAME = "get_stock_risk_flag";
    private static final String CALL_AUCTION_PRICE_TOOL_NAME = "get_call_auction_price";
    private static final String STOCK_CREDIT_INFO_TOOL_NAME = "get_stock_credit_info";
    private static final String ETF_INFO_TOOL_NAME = "get_etf_info";
    private static final String PROGRAM_TRADING_SUMMARY_TOOL_NAME = "get_program_trading_summary";
    private static final String INVESTOR_TREND_SUMMARY_TOOL_NAME = "get_investor_trend_summary";
    private static final String NEW_LISTING_STOCKS_TOOL_NAME = "get_new_listing_stocks";
    private static final String SHORT_SELLING_TREND_TOOL_NAME = "get_short_selling_trend";
    private static final String STOCK_MASTER_INFO_TOOL_NAME = "get_stock_master_info";
    private static final String INDUSTRY_INFO_TOOL_NAME = "get_industry_info";

    // 실제 API로 검증한 결과(2026-08-03), Gemini는 도구 실행 결과가 마음에 안 들면 검색어를
    // 바꿔 도구를 다시 요청하는 등 한 턴에 도구 호출을 여러 번 반복할 수 있었다 — "판단 1번 +
    // 도구실행 1번"으로 끝난다고 가정할 수 없다. 무한 루프를 막기 위해 도구 호출을 최대 이
    // 횟수까지만 허용하고, 다다르면 그 다음 호출에서는 tools를 아예 안 줘서 Gemini가
    // functionCall을 낼 수 없게(=텍스트로 답할 수밖에 없게) 강제 종료한다.
    //
    // 2026-08-04: 무료 등급 Gemini API 키의 일일 호출 한도가 매우 낮다는 것을 실제 테스트로
    // 확인했다(하루 30여 회 만에 소진, 이후 수 분간 재시도해도 해제 안 됨). 그래서 이 상한을
    // 3에서 1로 낮췄는데, "라운드 1번 = 도구 하나만 쓸 수 있다"는 뜻이 아니다 — 한 라운드
    // 안에서도 Gemini는 여러 도구를 동시에(parallel function calling) 요청할 수 있고,
    // converseWithTools()가 그 요청받은 도구들을 전부 함께 실행한다. 즉 "여러 API가 필요한
    // 질문이면 그 여러 API를 한 라운드 안에서 동시에, 각각 딱 한 번씩만" 부르는 게 목표이지,
    // 필요한 도구를 줄이는 게 목표가 아니다. 이 상한이 실제로 막는 것은 "도구 결과를 보고
    // 마음에 안 들어서 같은/다른 도구를 순차적으로 또 요청하는" 반복 왕복이며, 최악의 경우도
    // "도구 1라운드(도구 몇 개든 동시에) + 강제 마지막 응답" 2번으로 끝난다 — 유료 등급으로
    // 전환하기 전까지의 임시 조치다.
    private static final int MAX_TOOL_CALL_ROUNDS = 1;

    /**
     * "판단 로직"의 핵심 — 사용자 문장을 원문 그대로 검색어로 쓰지 않고, Gemini 스스로 이
     * 도구의 설명을 읽고 (1) 지금 뉴스 검색이 필요한지, (2) 필요하다면 어떤 검색어로 찾을지
     * 결정하게 한다. description에 "종목이 애매하면 쓰지 말고 되물어라"까지 명시해, "SK"처럼
     * 특정이 안 되는 질문에서 엉뚱한 종목으로 검색해버리는 문제를 막는다.
     */
    // 뉴스 관련성 조건을 우선순위 3단계로 나눠 파라미터 자체를 분리했다(NaverNewsSearchRequest
    // javadoc 참고) — 예전에는 회사명+주제를 한 문자열(query)로 뭉쳐서 보내고 공백으로
    // 쪼개 처리했는데, 그러면 "뭐가 필수고 뭐가 상황에 따른 조건인지"가 코드로 드러나지
    // 않아 판단 기준을 조정하기 어려웠다(2026-08-05).
    //
    // 아래 description은 실제 사용자가 던질 법한 뉴스 질문을 7개 축(종목 특정 방식/주제 표현
    // 방식/기간 표현 방식/대화 맥락 의존/질문의 화법/복수 요청 결합/실제 의도가 다른 경우)으로
    // 나눠 34개 세부 유형을 전수 조사했다(2026-08-05). 처음에는 "그룹명 모호/업종명/지수·시장/
    // 규모군 통칭"을 각각 별개 사례로 나열했는데, 그러면 목록에 없는 새로운 표현(예: "중소기업")이
    // 나올 때마다 사례를 계속 추가해야 하는 구조였다. 근본 원인은 이 사례들이 전부 "종목 특정성
    // (얼마나 좁게 하나 또는 소수의 상장사로 좁혀지는가)"이라는 하나의 축 위에 있다는 것 — 그래서
    // 개별 사례 나열 대신 이 판단 기준 하나를 먼저 세우고, 예시는 그 기준을 예시로만 뒷받침하는
    // 구조로 다시 짰다. DISCLOSURE_TOOL이 70여 개 공시 라벨을 나열하고 "가장 가까운 것을
    // 골라라"고 하는 것과 같은 안전망(목록에 없는 표현도 커버)은 유지하되, "종목 특정" 부분만큼은
    // 나열이 아니라 판단 기준으로 처리한다. 목표는 "정의 안 된 케이스"를 남기지 않는 것이다 —
    // 실제로 데이터를 못 찾는 경우가 있더라도, 최소한 "이 질문을 어떻게 다뤄야 하는지"에 대한
    // 판단 자체는 항상 내려지게 한다.
    private static final GeminiRequest.ToolDeclaration NEWS_SEARCH_TOOL = new GeminiRequest.ToolDeclaration(
            NEWS_SEARCH_TOOL_NAME,
            "사용자가 특정 종목이나 시장 상황에 대한 최신 뉴스·시황이 필요하다고 판단될 때, 증권 전문 매체의 "
                    + "관련 뉴스를 검색한다.\n\n"
                    + "[종목 특정성 판단 — 가장 먼저, 모든 경우에 공통 적용]\n"
                    + "사용자가 말한 대상이 '하나의 특정 상장사' 또는 '소수의 명확한 대표 종목들'로 자연스럽게 "
                    + "좁혀지는지 먼저 판단해라. 이건 개별 사례를 외워서 판단하는 게 아니라, 표현이 무엇이든 "
                    + "이 기준 하나로 통일해서 판단한다.\n"
                    + "- 특정성이 높다(좁혀진다) → 아래 [종목명을 정규화해서 채우는 방법]대로 companyName을 "
                    + "채우거나, 대표 종목이 여러 개면 그 수만큼 이 도구를 동시에(병렬로) 요청해라. 예: 정확한 "
                    + "회사명, 축약형·별칭, 영문명·티커, 옛 사명, 특정 종목들을 명시적으로 나열/비교하는 질문, "
                    + "'정유주'·'항공주'처럼 업종이라도 대표 상장사 2~3개를 자연스럽게 꼽을 수 있는 경우.\n"
                    + "- 특정성이 낮다(안 좁혀진다) → 이 도구를 쓰지 말고, 개별 회사가 여럿이라 하나로 정하기 "
                    + "어려운 경우(그룹명처럼 계열사가 많은 경우)는 사용자에게 되묻고, 애초에 개별 상장사 "
                    + "묶음이 아닌 경우(지수·시장 전체, ETF·펀드, '중소기업'·'대기업'·'자영업'처럼 업종이 아니라 "
                    + "규모로 뭉뚱그린 통칭 등)는 '이 도구로는 특정 종목 없이 답하기 어렵다'고 안내해라. 대표 "
                    + "종목을 무리하게 몇 개 골라서 마치 전체를 조사한 것처럼 답하지 마라 — 그 몇 개가 전체를 "
                    + "대표하지 못하면 오히려 잘못된 인상을 준다.\n"
                    + "- 애매하지만 판단이 가능한 경우(예: 예시 종목 하나를 들며 업종 전체를 묻는 '현대오일뱅크 "
                    + "같은 정유주들')는 그 예시 종목으로 검색하되, 최종 답변에서 '업종 전반'을 묻는 취지였음을 "
                    + "감안해 설명해라. 인물명 중심 질문(예: '이재용 회장 관련 소식')은 그 인물이 속한 회사명으로 "
                    + "대신 검색하되 인물 개인 뉴스까지는 못 찾을 수 있다고 안내해라.\n\n"
                    + "[종목명을 정규화해서 채우는 방법]\n"
                    + "- 축약형·별칭(예: '삼전'→삼성전자, '카뱅'→카카오뱅크, '하이닉스'→SK하이닉스)은 정식 "
                    + "명칭으로 풀어써라.\n"
                    + "- 영문명·티커(예: 'Samsung Electronics', '005930')는 한글 정식 명칭으로 바꿔라.\n"
                    + "- 오래된 사명이나 합병·개명 이력이 있는 회사(예: '대우조선해양'→한화오션)는 알고 있는 "
                    + "최신 정식 명칭을 써라. 확신이 없으면 사용자가 말한 이름을 그대로 쓰되 최종 답변에서 "
                    + "사명이 바뀌었을 수 있다고 언급해라.\n"
                    + "- 비상장 계열사(예: 'SK온')는 시도는 하되, 못 찾으면 비상장이라 확인이 어렵다고 "
                    + "안내해라.\n\n"
                    + "[그 밖의 판단 원칙]\n"
                    + "- 미래를 예측해달라는 질문(예: '다음 분기는 어떨 것 같아?')은 뉴스 검색 대상이 아니다 "
                    + "— 이 도구를 쓰지 말고 알고 있는 사실을 바탕으로 직접 답해라.\n"
                    + "- 뉴스가 필요 없는 질문(심리 상담, 일반 투자 원칙 등)에는 이 도구를 쓰지 마라.\n"
                    + "- 겉보기엔 막연한 뉴스 질문 같아도(예: '삼성전자 요즘 어때?') 사용자가 실제로는 숫자로 "
                    + "된 실적이나 소유권 변동처럼 재무·공시 데이터를 원하는 뉘앙스면, 이 도구와 함께 "
                    + "get_financial_statements나 get_disclosure_info 등 다른 도구도 같이 요청해라.\n"
                    + "- 위에 정확히 없는 질문 형태가 오더라도, [종목 특정성 판단] 기준으로 먼저 판단하고 "
                    + "나머지는 가장 가까운 원칙을 따라라 — 판단 자체를 하지 않고 넘어가지 마라.",
            Map.of(
                    "companyName", new GeminiRequest.ParameterSpec(
                            "string",
                            "뉴스를 찾을 회사·종목의 정식 명칭. 예: '삼성전자'. 위 [종목 특정성 판단]으로 "
                                    + "특정성이 높다고 판단됐을 때만 채우며, [종목명을 정규화해서 채우는 방법]을 "
                                    + "따라 축약형·영문명·옛 사명 등을 정규화한 뒤 채운다. 이 값은 항상 필수다."),
                    "topic", new GeminiRequest.ParameterSpec(
                            "string",
                            "[판단 기준] 사용자 문장이 사실형이든, 호재·악재 같은 감성/방향성 표현이든, "
                                    + "완곡한 확인이든, '떡상'·'떡락' 같은 은어든 — 표현 형식은 무시하고 그 "
                                    + "밑에 깔린 실제 핵심 주제 하나를 짧은 키워드로 뽑아 채워라. 표현이 무엇이든 "
                                    + "이 기준 하나로 판단한다(예: '호재 있어?'→'실적 호조', '떡상 하나요'→'주가 "
                                    + "급등', '실적 어때?'→'실적'). 사용자가 이미 어떤 소문·정보를 갖고 확인하러 "
                                    + "왔다면(예: '~한다는 소문이 있던데 진짜야?') 그 핵심 키워드를 topic으로 넣고, "
                                    + "최종 답변에서 단순 나열이 아니라 사실 여부를 명확히 확인·정정하는 태도로 "
                                    + "답하도록 유도해라.\n"
                                    + "특정 주제 없이 포괄적으로 물었다면(예: '요즘 어때?', '근황', '별일 없지?') "
                                    + "이 값은 비워둬라 — 억지로 키워드를 만들면 실제 기사 제목에는 그런 포괄적인 "
                                    + "표현이 거의 그대로 나오지 않아 오히려 진짜 관련 있는 기사까지 다 걸러진다.\n"
                                    + "한 문장에 실제로 서로 다른 주제가 여러 개 섞여 있으면(예: '실적도 궁금하고 "
                                    + "소송 건도 궁금해') 주제 개수만큼 이 도구를 각각 동시에(병렬로) 요청해라 "
                                    + "— topic 하나에 여러 주제를 욱여넣지 마라."),
                    "periodDays", new GeminiRequest.ParameterSpec(
                            "integer",
                            "[판단 기준] 사용자가 말한 시간 표현이 무엇이든 오늘 날짜를 기준으로 실제 일수로 "
                                    + "환산해서 채워라 — '오늘'→1, '이번주'→7, '3개월'→90, '작년'→365처럼 직접 "
                                    + "계산하고, '8월 1일에 무슨 일 있었어'처럼 특정 날짜면 오늘부터 그 날짜까지의 "
                                    + "일수를 계산해라. 1~730일(2년) 범위를 벗어나면(예: '3년 전엔 무슨 일 있었어') "
                                    + "가장 가까운 경계값(이 경우 730)을 채워라 — 이때는 [뉴스 조회 기간 한계 안내] "
                                    + "규칙을 반드시 함께 따라야 한다. 기간을 아예 언급하지 않았거나 '최근'·'요즘'처럼 "
                                    + "상대적이고 모호하면 이 값은 비워둬라 — 기본값(30일)이 자동으로 적용된다.")),
            List.of("companyName"));

    /**
     * 뉴스 도구와 같은 "판단 위임" 원칙을 재무제표에도 동일하게 적용한 도구. 예전에는 DART를
     * 사용자 질문과 무관하게 항상 대표 보유종목 하나에 대해서만 고정 호출했는데(비대칭 구조),
     * 이제는 Tavily와 마찬가지로 Gemini가 사용자 질문에서 실제로 어떤 회사의 재무 상태가
     * 궁금한지 판단해 회사명을 직접 넘기게 한다.
     */
    // "최근 실적"과 "전반적 재무 상태"는 DART에서 서로 다른 보고서를 봐야 정확하다 — 연간
    // 사업보고서는 사업연도가 끝난 다음 해에야 공시돼 최신성이 떨어지고, 분기 보고서는 훨씬
    // 빠르게(분기 종료 후 45일 이내) 공시된다. AI가 질문 성격을 보고 둘 중 하나를 직접
    // 고르게 해서, 도구를 한 번만 불러도 질문에 맞는 기간의 데이터를 정확히 받게 한다.
    private static final String PERIOD_ANNUAL = "연간";

    // 2026-08-13 추가 — get_historical_price/get_foreign_institutional_trend/
    // get_short_selling_trend/get_market_liquidity_trend/get_industry_info(TREND) 5개 도구가
    // 공통으로 쓰는 "긴 기간 조회" 파라미터. 원래 전부 최근 며칠(5~10일)로 하드코딩돼 있었는데,
    // "6개월간 저점/고점" 같은 질문에 실제로 답할 데이터가 없어 모델이 지어내는 문제가 실측돼
    // (라이브 테스트), 뉴스 검색의 2년 상한과 동일한 원칙으로 5개 도구 전부 최대 2년까지
    // 넓혔다. 짧은 기간 질문은 이 파라미터를 비워 기존 동작을 그대로 유지한다(하위 호환).
    private static final GeminiRequest.ParameterSpec PERIOD_MONTHS_PARAM = new GeminiRequest.ParameterSpec(
            "integer",
            "[판단 기준] 사용자가 '최근'·'요즘'처럼 짧은 기간을 의미하면 비워둬라(기본값 적용). "
                    + "'6개월간'·'1년간'처럼 구체적인 기간을 말했으면 개월 수로 환산해서 채워라(예: "
                    + "'6개월'→6, '1년'→12, '2년'→24). 1~24(2년) 범위를 벗어나면 가장 가까운 "
                    + "경계값을 채워라.");

    private static final GeminiRequest.ToolDeclaration FINANCIALS_TOOL = new GeminiRequest.ToolDeclaration(
            FINANCIALS_TOOL_NAME,
            "사용자가 특정 회사의 매출액·영업이익·순이익 등 실적이나 재무 상태가 궁금하다고 판단될 때, "
                    + "Open DART 공시 기준 재무제표를 조회한다. 어떤 회사를 말하는지 애매하면 이 도구를 쓰지 "
                    + "말고 먼저 사용자에게 되물어라. 재무 정보가 필요 없는 질문에는 쓰지 마라.",
            Map.of(
                    "companyName", new GeminiRequest.ParameterSpec(
                            "string",
                            "재무제표를 조회할 회사의 정식 명칭. 예: '삼성전자'. 종목코드가 아니라 회사명을 넣는다."),
                    "period", new GeminiRequest.ParameterSpec(
                            "string",
                            "조회할 기간. 반드시 '분기' 또는 '연간' 중 하나만 넣는다. 사용자가 '최근 실적', "
                                    + "'요즘 실적'처럼 최신 성과를 물으면 '분기'를, '재무 상태', '자산·부채' 같은 "
                                    + "전반적인 재무 건전성을 물으면 '연간'을 선택한다. 애매하면 '분기'를 선택한다 "
                                    + "— 사용자는 보통 최신 소식을 궁금해한다.")),
            List.of("companyName", "period"));

    /**
     * DART의 76개 공시 API 중 "자본변동"(유상증자/무상증자)만 우선 다루는 카테고리 도구. 76개를
     * 낱개 도구로 다 만들면 AI가 고를 도구가 너무 많아져 판단 자체가 흐려지므로, 성격이 비슷한
     * 공시들을 하나의 도구 + "종류" 파라미터로 묶는 방식을 쓴다(FINANCIALS_TOOL의 period 파라미터와
     * 같은 패턴). 나머지 카테고리(채권발행, 기업 주요 이벤트 등)는 이후 같은 패턴으로 추가한다.
     */
    private static final GeminiRequest.ToolDeclaration CAPITAL_CHANGE_TOOL = new GeminiRequest.ToolDeclaration(
            CAPITAL_CHANGE_TOOL_NAME,
            "사용자가 특정 회사의 유상증자(돈을 받고 새 주식을 발행하는 것)나 무상증자(주주에게 공짜로 "
                    + "새 주식을 나눠주는 것) 여부·내역이 궁금하다고 판단될 때, 최근 2년간의 관련 공시를 "
                    + "조회한다. 어떤 회사를 말하는지 애매하면 이 도구를 쓰지 말고 먼저 사용자에게 되물어라.",
            Map.of(
                    "companyName", new GeminiRequest.ParameterSpec(
                            "string",
                            "조회할 회사의 정식 명칭. 예: '삼성전자'. 종목코드가 아니라 회사명을 넣는다."),
                    "changeType", new GeminiRequest.ParameterSpec(
                            "string",
                            "반드시 '유상증자' 또는 '무상증자' 중 하나만 넣는다. 사용자가 어느 쪽인지 명시하지 "
                                    + "않고 그냥 '증자'라고만 물으면, 투자자 입장에서 더 자주 관심사가 되는 "
                                    + "'유상증자'를 기본으로 선택한다.")),
            List.of("companyName", "changeType"));

    /**
     * DART "소유권" 카테고리 도구 — 최대주주 현황/변동. 최대주주가 누구인지(소유주) 궁금할 때 쓴다.
     */
    private static final GeminiRequest.ToolDeclaration OWNERSHIP_TOOL = new GeminiRequest.ToolDeclaration(
            OWNERSHIP_TOOL_NAME,
            "사용자가 특정 회사의 최대주주(누가 회사를 소유·지배하고 있는지)나 소유주 변경 이력이 "
                    + "궁금하다고 판단될 때 조회한다. 어떤 회사를 말하는지 애매하면 이 도구를 쓰지 말고 "
                    + "먼저 사용자에게 되물어라.",
            Map.of(
                    "companyName", new GeminiRequest.ParameterSpec(
                            "string",
                            "조회할 회사의 정식 명칭. 예: '삼성전자'. 종목코드가 아니라 회사명을 넣는다."),
                    "infoType", new GeminiRequest.ParameterSpec(
                            "string",
                            "반드시 '현황' 또는 '변동' 중 하나만 넣는다. '지금 최대주주가 누구인지'처럼 현재 "
                                    + "상태를 물으면 '현황'을, '최대주주가 바뀐 적 있는지'처럼 이력을 물으면 "
                                    + "'변동'을 선택한다. 애매하면 '현황'을 선택한다.")),
            List.of("companyName", "infoType"));

    /**
     * DART 개발가이드의 나머지 API(배당, 임원현황, 회사채 발행, 소송, 합병·분할, 부도, 자사주 취득 등
     * 70여 개)를 하나로 묶은 범용 도구. 재무제표/자본변동/소유권 세 도구가 다루는 주제가 아니면
     * 전부 이 도구로 온다 — disclosureType에 넣을 수 있는 값은 DartApiClient.DISCLOSURE_REGISTRY에
     * 등록된 라벨 목록과 정확히 일치해야 하므로, 그 목록을 여기서 그대로 가져와 설명문에 나열한다
     * (등록 목록이 바뀌면 이 설명도 자동으로 같이 바뀐다 — 값을 손으로 이중 관리하지 않는다).
     */
    private static final GeminiRequest.ToolDeclaration DISCLOSURE_TOOL = new GeminiRequest.ToolDeclaration(
            DISCLOSURE_TOOL_NAME,
            "사용자가 특정 회사의 공시 정보 중, 재무제표/자본변동(증자)/소유권(최대주주) 세 가지가 "
                    + "아닌 나머지 주제(배당, 임원·직원 현황, 회사채 등 각종 증권 발행, 소송, 부도, "
                    + "합병·분할, 자사주 취득·처분, 해외상장 등)가 궁금하다고 판단될 때 사용한다. "
                    + "disclosureType에는 아래 목록에 있는 값 중 정확히 하나만 넣어야 한다 — 사용자가 "
                    + "전문용어를 쓰지 않고 애매하게 물어도(예: '이 회사 빚 많아요?') 괄호 안 설명을 "
                    + "보고 뜻이 가장 가까운 항목을 골라라. 목록에 없는 주제를 물으면 이 도구를 쓰지 "
                    + "말고 '해당 정보는 확인할 수 없다'고 답해라. "
                    + "**이름이 비슷한 라벨이 여러 개 있는 주제(2026-08-13 추가 — 라이브 테스트로 "
                    + "'인수합병 있었나요?'에 회사합병결정/회사분할합병결정/합병증권신고서 중 "
                    + "하나만 골랐다가 진짜 데이터가 있는 다른 라벨을 놓쳐 '없다'고 잘못 답한 사례가 "
                    + "실측됨)에는 절대 하나만 찍어서 시도하지 마라 — 후보가 될 만한 라벨을 전부(같은 "
                    + "주제로 묶인 라벨 개수만큼) 각각 별도의 get_disclosure_info 호출로 동시에(병렬로) "
                    + "요청하고, 그중 실제로 결과가 있는 것만 답에 반영해라. 특히 합병/분할 관련 질문은 "
                    + "회사합병결정·회사분할합병결정·회사분할결정·합병증권신고서·분할증권신고서 다섯 "
                    + "개 중 관련성 있는 걸 전부 함께 조회해라(무증자 합병처럼 증권신고서 제출이 "
                    + "필요 없는 방식이면 신고서 쪽엔 데이터가 없는 게 정상이다 — 그것도 하나의 "
                    + "유효한 결과다).** 목록(라벨(뜻)): "
                    + String.join(", ", DartApiClient.getSupportedDisclosureTypesWithDescription()),
            Map.of(
                    "companyName", new GeminiRequest.ParameterSpec(
                            "string",
                            "조회할 회사의 정식 명칭. 예: '삼성전자'. 종목코드가 아니라 회사명을 넣는다."),
                    "disclosureType", new GeminiRequest.ParameterSpec(
                            "string",
                            "위 도구 설명에 나열된 목록에 있는 값 중 정확히 하나. 목록에 없는 표현(동의어 등)을 "
                                    + "임의로 만들어내지 말고, 목록에서 가장 가까운 항목을 골라라.")),
            List.of("companyName", "disclosureType"));

    /**
     * LS증권 Open API로 종목 현재가를 그때그때 REST 조회하는 도구(LsMarketDataApiClient 참고,
     * 2026-08-07 추가, 2026-08-10 PER/PBR/52주 최고·최저/상장주식수/외국인 소진율 추가). 장중이면
     * 실시간 체결가, 장 마감 후라면 LS가 돌려주는 마지막 체결가를 받는다. DART/네이버와 달리
     * 시점에 따라 값이 계속 바뀌는 조회라 executeTool()의 세션 캐시(30분 TTL) 대상에서 제외한다
     * — CURRENT_PRICE_TOOL_NAME 분기 참고.
     */
    private static final GeminiRequest.ToolDeclaration CURRENT_PRICE_TOOL = new GeminiRequest.ToolDeclaration(
            CURRENT_PRICE_TOOL_NAME,
            "사용자가 특정 종목의 지금 현재가·주가·시세, 또는 PER·PBR·52주 최고가/최저가·상장주식수· "
                    + "외국인 보유한도 소진율이 궁금하다고 판단될 때 사용한다. 재무제표(매출·이익 등 "
                    + "공시 기준 실적)나 공시가 아니라, 지금 이 순간의 체결가와 그 가격에서 바로 계산되는 "
                    + "시세 지표를 물을 때만 써라. 장중이면 실시간 가격, 장이 닫혀있으면 마지막으로 "
                    + "체결된 가격을 돌려주며, 조회 자체가 실패하면 결과가 없을 수 있다.",
            Map.of("companyName", new GeminiRequest.ParameterSpec(
                    "string",
                    "조회할 회사의 정식 명칭. 예: '삼성전자'. 종목코드가 아니라 회사명을 넣는다.")),
            List.of("companyName"));

    /**
     * LS증권 Open API 외인기관종목별동향(t1716) 조회 도구(LsInvestorTrendApiClient 참고,
     * 2026-08-10 추가). DART의 get_ownership_info(대량보유상황보고 — 특정 투자자가 5% 이상
     * 보유하게 됐을 때만 나오는 개별 신고)와 명확히 다른 주제라는 걸 설명에 명시해, "외국인이
     * 얼마나 갖고 있어?" 같은 질문에서 두 도구가 겹쳐 보이지 않게 한다.
     */
    private static final GeminiRequest.ToolDeclaration FOREIGN_INSTITUTIONAL_TREND_TOOL = new GeminiRequest.ToolDeclaration(
            FOREIGN_INSTITUTIONAL_TREND_TOOL_NAME,
            "사용자가 특정 종목을 최근 외국인이나 기관이 사고 있는지(순매수 동향), 또는 외국인 "
                    + "보유한도 소진율이 궁금하다고 판단될 때 사용한다. 기본은 최근 10일간의 일별 KRX "
                    + "기준 외국인·기관·개인 순매수 수량과 외국인 보유한도 소진율을 준다. "
                    + "get_ownership_info(최대주주·5% 이상 대량보유 신고, 특정 투자자 1명을 다루는 "
                    + "개별 공시)와는 완전히 다른 도구다 — '최대주주가 누구인지'는 이 도구가 아니라 "
                    + "get_ownership_info를 써라. 이 도구는 특정 투자자 개인이 아니라 외국인·기관이라는 "
                    + "'그룹 전체'가 최근 실제로 사고팔았는지를 다룬다.",
            Map.of("companyName", new GeminiRequest.ParameterSpec(
                            "string",
                            "조회할 회사의 정식 명칭. 예: '삼성전자'. 종목코드가 아니라 회사명을 넣는다."),
                    "periodMonths", PERIOD_MONTHS_PARAM),
            List.of("companyName"));

    /**
     * LS증권 Open API 투자의견(t3401) 조회 도구(LsInvestInfoApiClient 참고, 2026-08-10 추가).
     * 뉴스(search_securities_news)가 "목표주가 상향" 같은 소식을 정성적 기사로 다룰 수 있지만,
     * 실제 수치(옛/새 투자의견, 옛/새 목표주가, 증권사명)가 필요한 질문은 이 도구로만 정확히
     * 답할 수 있다는 걸 설명에 명시해 역할을 분리한다.
     */
    private static final GeminiRequest.ToolDeclaration INVESTMENT_OPINION_TOOL = new GeminiRequest.ToolDeclaration(
            INVESTMENT_OPINION_TOOL_NAME,
            "사용자가 특정 종목에 대한 증권사들의 투자의견(매수/보유 등)이나 목표주가가 궁금하다고 "
                    + "판단될 때 사용한다. 최근 증권사별 투자의견·목표주가 변경 이력(증권사명, 발표일, "
                    + "변경 전/후 의견, 변경 전/후 목표주가)을 수치로 준다. search_securities_news와는 "
                    + "역할이 다르다 — 뉴스는 '목표주가를 올렸다더라' 같은 정성적 맥락·기사 텍스트를 "
                    + "주지만, 실제 정확한 목표주가 숫자나 증권사별 비교가 필요하면 반드시 이 도구를 "
                    + "함께 써라(사용자가 '진짜 얼마로 올렸대?'처럼 구체적 수치를 원하면 이 도구가 필수).",
            Map.of("companyName", new GeminiRequest.ParameterSpec(
                    "string",
                    "조회할 회사의 정식 명칭. 예: '삼성전자'. 종목코드가 아니라 회사명을 넣는다.")),
            List.of("companyName"));

    /**
     * LS증권 Open API 종목별증시일정(t3202) 중 주주총회만 필터링해 제공하는 도구
     * (LsInvestInfoApiClient.getShareholderMeetingSchedule() 참고, 2026-08-10 추가). t3202는
     * 배당·유상증자·감자·합병분할 등 14종 일정을 다 주지만, 그 항목들은 이미 DART 도구
     * (get_disclosure_info의 "배당사항", get_capital_change_info, "감자결정", "회사분할결정" 등)가
     * 담당하므로 이 도구는 DART가 구조화된 형태로 다루지 않는 "주주총회 날짜"만 노출한다 —
     * 배당 금액·증자 내역이 궁금하면 이 도구가 아니라 해당 DART 도구를 써야 한다는 걸 설명에
     * 명시해 혼동을 막는다.
     */
    private static final GeminiRequest.ToolDeclaration SHAREHOLDER_MEETING_TOOL = new GeminiRequest.ToolDeclaration(
            SHAREHOLDER_MEETING_TOOL_NAME,
            "사용자가 특정 종목의 주주총회가 언제 열렸는지·열릴 예정인지 궁금하다고 판단될 때만 "
                    + "사용한다. 이 도구는 오직 주주총회 날짜만 다룬다 — 배당 금액이 궁금하면 "
                    + "get_disclosure_info(disclosureType='배당사항')를, 유상증자·무상증자가 궁금하면 "
                    + "get_capital_change_info를 대신 써라. 이 도구로 배당·증자 관련 질문에 답하려고 "
                    + "하지 마라.",
            Map.of("companyName", new GeminiRequest.ParameterSpec(
                    "string",
                    "조회할 회사의 정식 명칭. 예: '삼성전자'. 종목코드가 아니라 회사명을 넣는다.")),
            List.of("companyName"));

    /**
     * 시장 전체를 한 조건으로 훑어 상위 N개 종목을 뽑는 7가지 랭킹(LsHighItemApiClient 참고)을
     * rankingType 하나로 묶은 도구(2026-08-11 추가). 시간외등락률/시간외거래량은 시간외 거래
     * 시간대(15:30~18:00)에만 실제 데이터가 의미 있어, 그 시간대가 아니면 LS를 호출하지 않고
     * "지금은 확인할 수 있는 시간이 아니다"로 안내한다(executeMarketRankingLookup() 참고).
     */
    private static final GeminiRequest.ToolDeclaration MARKET_RANKING_TOOL = new GeminiRequest.ToolDeclaration(
            MARKET_RANKING_TOOL_NAME,
            "사용자가 특정 종목이 아니라 '시장 전체'에서 어떤 종목이 상위권인지 궁금하다고 판단될 때 "
                    + "사용한다. rankingType으로 정확히 어떤 기준의 순위인지 골라야 한다 — '오늘 많이 "
                    + "오른/잘나가는 종목'처럼 애매하면 등락률(PRICE_CHANGE_RATE)을 기본으로 선택해라.",
            Map.of("rankingType", new GeminiRequest.ParameterSpec(
                    "string",
                    "다음 중 정확히 하나만 넣는다. PRICE_CHANGE_RATE(등락률 상위 — '오늘 많이 오른 "
                            + "종목'), MARKET_CAP(시가총액 상위 — '덩치 큰 종목'), VOLUME(거래량 상위 "
                            + "— '거래 많이 된 종목', 주식 수 기준), TRADING_VALUE(거래대금 상위 — "
                            + "'돈이 많이 몰린 종목', 금액 기준. VOLUME과 헷갈리지 마라 — 주식 수가 "
                            + "적어도 비싼 종목이면 거래대금은 클 수 있다), VOLUME_SURGE(전일 동시간대 "
                            + "대비 거래량 급증 — '갑자기 거래량 튄 종목'), AFTER_HOURS_PRICE_CHANGE_RATE "
                            + "(시간외 등락률 상위, 시간외 거래시간(15:30~18:00)에만 유효), "
                            + "AFTER_HOURS_VOLUME(시간외 거래량 상위, 마찬가지로 시간외 거래시간에만 "
                            + "유효).")),
            List.of("rankingType"));

    /**
     * 테마 관련 3가지 질문(테마명으로 구성종목 찾기/특정 종목이 속한 테마/오늘 핫테마)을 mode
     * 하나로 묶은 도구(LsSectorApiClient 참고, 2026-08-11 추가).
     */
    private static final GeminiRequest.ToolDeclaration THEME_INFO_TOOL = new GeminiRequest.ToolDeclaration(
            THEME_INFO_TOOL_NAME,
            "사용자가 테마·섹터(예: '2차전지', '반도체 장비', 'AI 관련주')에 대해 궁금하다고 "
                    + "판단될 때 사용한다. mode에 따라 필요한 파라미터가 다르다.",
            Map.of(
                    "mode", new GeminiRequest.ParameterSpec(
                            "string",
                            "다음 중 정확히 하나. THEME_TO_STOCKS('이 테마에 어떤 종목이 있어?' — "
                                    + "themeName 필수), STOCK_TO_THEMES('이 종목은 무슨 테마야?' — "
                                    + "companyName 필수), HOT_THEMES('오늘 핫한 테마 뭐야?' — 둘 다 불필요)."),
                    "themeName", new GeminiRequest.ParameterSpec(
                            "string", "mode가 THEME_TO_STOCKS일 때만 채운다. 예: '2차전지'."),
                    "companyName", new GeminiRequest.ParameterSpec(
                            "string", "mode가 STOCK_TO_THEMES일 때만 채운다. 정식 회사명. 예: '삼성전자'.")),
            List.of("mode"));

    /**
     * 재무지표(ROE/PER/PBR 등) 기준 전체 종목 랭킹(t3341, 2026-08-11 추가). 앞서 "시장 전체
     * 랭킹은 LS에 없다"고 판단했던 게, 재무지표 한정으로는 실제로 존재함이 확인된 도구다.
     */
    private static final GeminiRequest.ToolDeclaration FINANCIAL_RANKING_TOOL = new GeminiRequest.ToolDeclaration(
            FINANCIAL_RANKING_TOOL_NAME,
            "사용자가 특정 종목이 아니라 '시장 전체'에서 ROE·PER·PBR 등 재무지표 기준으로 상위권인 "
                    + "종목이 궁금하다고 판단될 때 사용한다(예: 'ROE 높은 종목 알려줘', '저평가된 "
                    + "종목 뭐 있어?'). 특정 회사 하나의 재무 상태가 궁금하면 이 도구가 아니라 "
                    + "get_financial_statements를 써라.",
            Map.of("criteria", new GeminiRequest.ParameterSpec(
                    "string",
                    "다음 중 정확히 하나만 넣는다: SALES_GROWTH(매출액증가율), "
                            + "OPERATING_INCOME_GROWTH(영업이익증가율), DEBT_RATIO(부채비율), EPS, BPS, "
                            + "ROE, PER, PBR, PEG. '저평가'라는 표현이면 PER나 PBR을 골라라. 애매하면 ROE.")),
            List.of("criteria"));

    /** 해외지수·환율 현재가(t3521, 2026-08-11 추가). */
    private static final GeminiRequest.ToolDeclaration OVERSEAS_INDEX_TOOL = new GeminiRequest.ToolDeclaration(
            OVERSEAS_INDEX_TOOL_NAME,
            "사용자가 다우지수·나스닥·원달러 환율 등 해외 지수나 환율의 지금 시세가 궁금하다고 "
                    + "판단될 때 사용한다. '최근 며칠간 흐름'처럼 과거 추이를 물으면 이 도구로는 "
                    + "오늘 시점 값만 줄 수 있다고 안내해라(시계열 데이터는 제공하지 않는다).",
            Map.of("indexName", new GeminiRequest.ParameterSpec(
                    "string",
                    "다음 중 사용자 질문과 가장 가까운 것 하나: '다우지수', '나스닥', '원달러환율', "
                            + "'국제유가'. 목록에 정확히 없는 지수를 물으면 이 도구를 쓰지 말고 확인할 "
                            + "수 없다고 답해라.")),
            List.of("indexName"));

    /** 증시 대기자금(고객예탁금·신용융자잔고) 최근 추이(t8428, 2026-08-11 추가). */
    private static final GeminiRequest.ToolDeclaration MARKET_LIQUIDITY_TOOL = new GeminiRequest.ToolDeclaration(
            MARKET_LIQUIDITY_TOOL_NAME,
            "사용자가 '요즘 증시 대기자금이 많은지', '고객예탁금이 늘었는지', '신용융자 잔고가 "
                    + "어떤지'처럼 시장 전체의 투자 대기자금 상황이 궁금하다고 판단될 때 사용한다. "
                    + "기본은 최근 5거래일 수치를 준다 — 짧은 기간이면 숫자를 나열하지 말고 늘었는지 "
                    + "줄었는지 추세로 요약해서 답해라. periodMonths로 긴 기간을 요청하면 그 기간의 "
                    + "최고/최저·전체 추이를 함께 준다.",
            Map.of("periodMonths", PERIOD_MONTHS_PARAM),
            List.of());

    private static final GeminiRequest.ToolDeclaration TECHNICAL_SIGNAL_TOOL = new GeminiRequest.ToolDeclaration(
            TECHNICAL_SIGNAL_TOOL_NAME,
            "사용자가 특정 종목의 지지선·저항선(피봇)이 궁금하다고 판단될 때 사용한다. 이동평균선처럼 "
                    + "이 도구가 다루지 않는 다른 기술적 지표를 물으면 쓰지 말고, 그건 데이터 신뢰도 "
                    + "문제로 안내해드리기 어렵다고 [확인해줄 수 없는 요청을 안내하는 방식]대로 답해라.",
            Map.of("companyName", new GeminiRequest.ParameterSpec("string", "정식 회사명. 예: '삼성전자'.")),
            List.of("companyName"));

    private static final GeminiRequest.ToolDeclaration HISTORICAL_PRICE_TOOL = new GeminiRequest.ToolDeclaration(
            HISTORICAL_PRICE_TOOL_NAME,
            "사용자가 특정 종목의 시세 흐름(시가총액, 외국인/개인 순매수 포함)이나 '최근 N개월간 "
                    + "저점/고점이 얼마였는지'가 궁금하다고 판단될 때 사용한다. 기본은 최근 5거래일 "
                    + "일봉을 준다 — 짧은 기간이면 날짜별 숫자를 나열하지 말고 흐름을 요약해서 답해라. "
                    + "periodMonths로 긴 기간(6개월 이상)을 요청하면 그 기간의 실제 최고가·최저가를 "
                    + "월봉 기준으로 계산해서 함께 준다 — '저점/고점' 질문에는 이 필드를 반드시 채워라, "
                    + "비워두면 최근 5거래일치만 와서 정확한 저점/고점을 답할 수 없다.",
            Map.of(
                    "companyName", new GeminiRequest.ParameterSpec("string", "정식 회사명. 예: '삼성전자'."),
                    "periodMonths", PERIOD_MONTHS_PARAM),
            List.of("companyName"));

    private static final GeminiRequest.ToolDeclaration MULTI_STOCK_PRICE_TOOL = new GeminiRequest.ToolDeclaration(
            MULTI_STOCK_PRICE_TOOL_NAME,
            "사용자가 여러 종목의 현재가를 한번에 물어볼 때(예: '내가 가진 종목들 지금 얼마야?', "
                    + "'삼성전자랑 SK하이닉스 지금 얼마야?') 사용한다. 최대 5종목까지 한 번에 조회한다.",
            Map.of("companyNames", new GeminiRequest.ParameterSpec(
                    "string", "쉼표로 구분한 회사명 목록. 예: '삼성전자,SK하이닉스'. 최대 5개.")),
            List.of("companyNames"));

    private static final GeminiRequest.ToolDeclaration RISK_FLAG_TOOL = new GeminiRequest.ToolDeclaration(
            RISK_FLAG_TOOL_NAME,
            "사용자가 특정 종목이 관리종목·투자경고·매매정지 등 위험 신호가 있는지 궁금하다고 "
                    + "판단될 때 사용한다(예: '이 종목 위험해요?', '관리종목 아니에요?', '매매정지된 "
                    + "거 아니에요?').",
            Map.of("companyName", new GeminiRequest.ParameterSpec("string", "정식 회사명. 예: '삼성전자'.")),
            List.of("companyName"));

    /** 동시호가 시간대(08:30~09:00)에만 유효 — 그 외 시간대는 게이트로 막는다. */
    private static final GeminiRequest.ToolDeclaration CALL_AUCTION_PRICE_TOOL = new GeminiRequest.ToolDeclaration(
            CALL_AUCTION_PRICE_TOOL_NAME,
            "사용자가 동시호가(장 시작 전) 예상체결가가 궁금하다고 판단될 때 사용한다. 동시호가 "
                    + "시간대(08:30~09:00)가 아니면 결과가 '지금은 확인할 수 없다'로 온다.",
            Map.of("companyName", new GeminiRequest.ParameterSpec("string", "정식 회사명. 예: '삼성전자'.")),
            List.of("companyName"));

    private static final GeminiRequest.ToolDeclaration STOCK_CREDIT_INFO_TOOL = new GeminiRequest.ToolDeclaration(
            STOCK_CREDIT_INFO_TOOL_NAME,
            "사용자가 특정 종목의 담보대출 가능 여부, 매수 증거금률, 신용거래 동향, 대차거래(공매도 "
                    + "준비물량) 추이 중 하나가 궁금하다고 판단될 때 사용한다.",
            Map.of(
                    "companyName", new GeminiRequest.ParameterSpec("string", "정식 회사명. 예: '삼성전자'."),
                    "infoType", new GeminiRequest.ParameterSpec(
                            "string",
                            "다음 중 정확히 하나: COLLATERAL_LOAN(담보대출 가능 여부), "
                                    + "MARGIN_REQUIREMENT(매수 증거금률), MARGIN_TRADING(신용거래 동향 — 기간 "
                                    + "확장 미지원, 항상 최근 며칠만 조회됨), SECURITIES_LENDING(대차거래 추이 — "
                                    + "periodMonths로 기간 확장 가능)."),
                    "periodMonths", new GeminiRequest.ParameterSpec(
                            "integer",
                            "infoType이 SECURITIES_LENDING일 때만 사용. " + PERIOD_MONTHS_PARAM.description())),
            List.of("companyName", "infoType"));

    private static final GeminiRequest.ToolDeclaration ETF_INFO_TOOL = new GeminiRequest.ToolDeclaration(
            ETF_INFO_TOOL_NAME,
            "사용자가 특정 ETF의 현재가(NAV 포함)나 구성종목이 궁금하다고 판단될 때 사용한다.",
            Map.of(
                    "companyName", new GeminiRequest.ParameterSpec("string", "ETF 정식 명칭. 예: 'KODEX 200'."),
                    "infoType", new GeminiRequest.ParameterSpec(
                            "string", "PRICE(현재가) 또는 CONSTITUENTS(구성종목) 중 하나.")),
            List.of("companyName", "infoType"));

    private static final GeminiRequest.ToolDeclaration PROGRAM_TRADING_SUMMARY_TOOL = new GeminiRequest.ToolDeclaration(
            PROGRAM_TRADING_SUMMARY_TOOL_NAME,
            "사용자가 프로그램매매(기관 등의 컴퓨터 자동매매) 동향이 궁금하다고 판단될 때 사용한다.",
            Map.of("mode", new GeminiRequest.ParameterSpec(
                    "string",
                    "MARKET_SNAPSHOT(시장 전체 순매수 스냅샷 — '오늘 프로그램매매 순매수 얼마예요?') "
                            + "또는 TOP_STOCKS(순매수 상위 종목 랭킹 — '프로그램매매로 많이 산 종목 뭐예요?') "
                            + "중 하나.")),
            List.of("mode"));

    private static final GeminiRequest.ToolDeclaration INVESTOR_TREND_SUMMARY_TOOL = new GeminiRequest.ToolDeclaration(
            INVESTOR_TREND_SUMMARY_TOOL_NAME,
            "사용자가 특정 종목이 아니라 시장 전체의 오늘 투자자별(개인/외국인/기관) 매매 동향이 "
                    + "궁금하다고 판단될 때 사용한다. 특정 종목의 외국인/기관 동향은 이 도구가 아니라 "
                    + "get_foreign_institutional_trend를 써라.",
            Map.of("mode", new GeminiRequest.ParameterSpec(
                    "string",
                    "BY_INVESTOR_TYPE(개인/외국인/기관 등 유형별 순매수 — '오늘 외국인 얼마나 "
                            + "샀어요?') 또는 BY_MARKET(코스피/코스닥/선물 등 시장별 비교 — '코스피랑 "
                            + "코스닥 중 어디에 더 샀어요?') 중 하나.")),
            List.of("mode"));

    private static final GeminiRequest.ToolDeclaration NEW_LISTING_STOCKS_TOOL = new GeminiRequest.ToolDeclaration(
            NEW_LISTING_STOCKS_TOOL_NAME,
            "사용자가 새로 상장한 종목이 궁금하다고 판단될 때 사용한다. 기본은 최근 6개월치를 준다.",
            Map.of("periodMonths", PERIOD_MONTHS_PARAM), List.of());

    private static final GeminiRequest.ToolDeclaration SHORT_SELLING_TREND_TOOL = new GeminiRequest.ToolDeclaration(
            SHORT_SELLING_TREND_TOOL_NAME,
            "사용자가 특정 종목의 공매도 거래량·비중이 궁금하다고 판단될 때 사용한다(예: '이 종목 "
                    + "공매도 많이 들어왔어요?'). 기본은 최근 5거래일 데이터를 준다. periodMonths로 "
                    + "긴 기간을 요청하면 그 기간의 합계·최고/최저일을 함께 준다.",
            Map.of(
                    "companyName", new GeminiRequest.ParameterSpec("string", "정식 회사명. 예: '삼성전자'."),
                    "periodMonths", PERIOD_MONTHS_PARAM),
            List.of("companyName"));

    private static final GeminiRequest.ToolDeclaration STOCK_MASTER_INFO_TOOL = new GeminiRequest.ToolDeclaration(
            STOCK_MASTER_INFO_TOOL_NAME,
            "사용자가 특정 종목의 오늘 상한가/하한가 가격이나 스팩(SPAC) 여부가 궁금하다고 판단될 "
                    + "때 사용한다. 그 외 목적(PER/PBR 등)에는 쓰지 마라 — get_current_price를 써라.",
            Map.of("companyName", new GeminiRequest.ParameterSpec("string", "정식 회사명. 예: '삼성전자'.")),
            List.of("companyName"));

    /**
     * 업종 시세 통합 도구(2026-08-11 추가) — 업종현재가/최근 추이/예상지수(게이트) 3개 TR을
     * mode로 묶는다. marketName은 2026-08-11 기준 코스피/코스닥만 지원한다
     * (LsIndustryApiClient.INDUSTRY_CODE_BY_NAME 참고).
     */
    private static final GeminiRequest.ToolDeclaration INDUSTRY_INFO_TOOL = new GeminiRequest.ToolDeclaration(
            INDUSTRY_INFO_TOOL_NAME,
            "사용자가 코스피·코스닥 지수(개별 종목이 아니라 시장 전체 지수) 관련 정보가 궁금하다고 "
                    + "판단될 때 사용한다.",
            Map.of(
                    "marketName", new GeminiRequest.ParameterSpec("string", "'코스피' 또는 '코스닥' 중 하나. 애매하면 '코스피'."),
                    "mode", new GeminiRequest.ParameterSpec(
                            "string",
                            "CURRENT(지금 지수 — '코스피 지금 얼마예요?'), TREND(기간별 흐름 — "
                                    + "'코스피 최근 어땠어요?'), EXPECTED(동시호가 예상지수, 장전 08:30~09:00 "
                                    + "또는 장마감전 15:20~15:30에만 유효) 중 하나."),
                    "callAuctionSession", new GeminiRequest.ParameterSpec(
                            "string", "mode가 EXPECTED일 때만: '장전' 또는 '장후' 중 하나. 애매하면 '장전'."),
                    "periodMonths", new GeminiRequest.ParameterSpec(
                            "integer",
                            "mode가 TREND일 때만 사용. " + PERIOD_MONTHS_PARAM.description())),
            List.of("marketName", "mode"));

    private static final String SYSTEM_PREAMBLE = """
            너는 'AI STOCK' 모의투자 서비스의 AI 재무설계사다. 격식 차린 상담원이 아니라, 어려운
            내용도 친구처럼 편하게 풀어서 이해시켜주는 재무설계사처럼 말해라. 아래 사용자 정보를
            참고해서 친절하고 이해하기 쉽게, 투자 조언과 재무 설계를 함께 안내해라.

            [첫 인사]
            - "안녕하세요! AI 재무설계사 STOCK입니다" 같은 자기소개 겸 인사는 네가 직접 넣지
              마라(2026-08-11 변경 — 예전엔 "세션 첫 턴이면 인사해라"를 프롬프트로 요청했는데,
              라이브 테스트에서 가끔 빠뜨리는 사례가 확인돼 시스템이 코드로 직접 붙이는 방식으로
              바꿨다). 세션의 몇 번째 턴이든, 첫 턴이든 후속 턴이든 항상 인사 없이 바로
              본론부터 시작해라 — 인사말은 이 프롬프트 밖에서 시스템이 필요할 때만 자동으로
              앞에 붙인다.

            [도구 호출과 함께 보내는 텍스트]
            - 도구를 호출하면서 텍스트를 같이 보내야 한다면, "확인해볼게요", "찾아볼게요" 같은
              한 문장으로 짧게 끝내라. 어떤 도구를 쓸지, 몇 개를 호출하는지, "도구를 사용합니다"
              같은 내부 처리 과정을 목록이나 괄호로 나열하지 마라 — 사용자는 시스템이 어떻게
              동작하는지가 아니라 결과를 궁금해한다.
            - 절대 규칙: 도구가 필요하다고 판단했으면 반드시 실제 함수 호출(functionCall)로
              요청해야 한다 — "도구 호출:", "찾아볼게요 (결과는 나중에)", "[참고: 조회 결과
              반영 예정]"처럼 마치 도구를 부른 것처럼 텍스트로 흉내만 내고 실제 함수 호출
              없이 끝내는 답변은 절대 금지다(2026-08-07, 여러 회사·여러 도구가 겹치는 복잡한
              요청에서 실제로 이 문제가 발생함을 라이브 테스트로 확인). 회사가 여러 개거나
              필요한 도구가 여러 개라도 예외 없이 전부 실제 functionCall로 요청해라. 텍스트로
              결과를 지어내거나 빈 자리로 남겨두고 답변을 끝내느니, 차라리 조회 없이 아는
              지식 선에서 솔직하게 답하는 편이 낫다.

            [도구 사용 판단 원칙] (2026-08-06 통합 재정리, 2026-08-07 get_current_price 추가,
            2026-08-10 get_foreign_institutional_trend/get_investment_opinion/
            get_shareholder_meeting_schedule 3개 추가 반영 — 개별 도구 나열이 아니라 판단 기준
            하나로 모든 경우를 커버한다. 이전에는 "이 도구는 이럴 때 써라"만 나열해서, 뉴스
            도구와 공시 도구가 겹치는 주제(예: 배당·소송)를 어느 쪽으로 판단해야 하는지, 애초에
            스물일곱 도구 중 아무것도 필요 없는 질문(잡담, 서비스 사용법 등)을 어떻게 걸러내는지에
            대한 기준이 빠져 있었다. 2026-08-10에 추가된 3개 도구는 LS(외국인/기관 동향·투자의견·
            주주총회일정)와 DART(대량보유상황보고·뉴스·배당사항 등) 도구가 표면적으로 비슷한
            주제를 다루는 것처럼 보여 혼동될 위험이 있어, 아래 2단계에 "겹치는 주제 구분" 절을
            별도로 추가했다.)

            [판단보다 지시가 우선이다] (2026-08-13 추가 — "판단"은 애매하거나 정보가 부족할 때
            쓰라고 있는 것이지, 사용자가 이미 명확하게 말한 것까지 다시 판단하라는 게 아니다.
            **이 원칙은 아래 모든 단계·모든 도구에 공통으로, 항상 우선 적용된다.**)
            - 사용자가 대상(회사·종목)과 원하는 종류(시세/재무/공시/뉴스 등)를 이미 명확히
              말했으면, 그걸 다시 의심하거나 "정말 그걸 원하시나요?" 식으로 되묻지 말고 그대로
              실행해라. 판단이 필요한 지점은 오직 "사용자가 말 안 한 부분"(예: 공시 세부 항목,
              기간 등)뿐이고, 그 빈 자리는 [실제 데이터 예시를 요청받았을 때]처럼 네가 합리적인
              기본값을 정해서 채우고 실행해라 — 빈 자리가 있다는 이유로 명시된 부분까지 통째로
              보류하고 되묻지 마라.
              - 예: "삼성전자 공시 예시 보여줘" → 대상(삼성전자)과 종류(공시)는 명확하다. 세부
                항목(배당사항인지 임원현황인지)만 네가 정해서 바로 조회해라.
              - 예: "삼성전자 최근 3개월 시세 흐름 알려줘" → 대상·종류·기간이 다 명확하니 그대로
                get_historical_price(periodMonths=3)를 바로 호출해라, 되묻지 마라.
            - 이 원칙은 0단계의 [해외 상장 종목] 판단처럼 "도구를 쓰기 전에 먼저 걸러야 하는"
              규칙과는 별개다 — 해외 상장 종목 여부처럼 실행 자체를 막는 판단은 그대로
              유지되고, 이 원칙은 그 판단을 통과한 뒤 "어떤 세부 파라미터를 쓸지"에서 망설이지
              말라는 뜻이다.
            - **회사가 비상장이거나 데이터가 없을 것 같다는 네 나름의 추측 때문에 도구 호출
              자체를 생략하는 것도 절대 금지다(2026-08-13 추가 — "SK쉴더스는 비상장이라 안
              된다"고 도구를 아예 안 부르고 답한 사례가 실측됨. 실제로는 회사명 표기만 다르게
              등록돼 있었을 뿐 corp_code로 재무 데이터 조회가 실제로 가능했다). 국내 회사면
              "비상장일 것 같다"는 짐작만으로 판단하지 말고, 일단 실제로 도구를 호출해서 결과를
              직접 확인한 뒤에만 "확인이 안 된다"고 답해라 — 확인도 안 해보고 네 지식만으로
              결론 내리는 답변은 항상 오답이다.** 도구를 불러 실제로 빈 결과가 왔을 때만
              [구조적 미지원 vs 이번 조회 결과 없음 구분]을 따라 안내해라.
            - "판단"을 쓸 자리는 사용자가 정말 어느 회사·어느 도구를 원하는지조차 특정이 안 될
              때([애매한 질문에 되물을 때의 태도] 대상)로 한정해라. 대상이 이미 정해진 요청에
              "이 도구가 맞을지 모르겠다"며 망설이거나 개념 설명으로 도망치는 답변은 항상 오답이다.

            0단계 — 이 질문이 종목/기업/투자와 관련이 있는가?
            - 관련이 없으면(날씨, 잡담, "너는 누구야", 서비스 사용법 등) 스물일곱 도구를 전부
              쓰지 말고 평범한 대화로 답해라. 억지로 종목 이야기로 연결하지 마라.
            - 사용자가 언급한 회사가 국내(코스피·코스닥) 상장이 아니라 해외(미국 등)에 상장된
              기업으로 보이면(예: 테슬라, 애플, 엔비디아처럼 국내 증권거래소에 없는 해외 기업),
              스물일곱 도구 중 어느 것도 쓰지 마라 — 시세뿐 아니라 search_securities_news도
              포함이다(2026-08-13 변경: 예전엔 시세만 거절하고 뉴스는 별도로 찾아주던 방식이
              "이건 되고 저건 안 되고" 식으로 일관성 없다는 피드백을 받아, 해외 상장 종목은
              시세·뉴스를 한 번에 같은 안내로 통합했다). 도구를 쓰는 대신, 관심을 알아준 뒤
              (예: "OO에 관심이 있으시군요!") "국내(코스피·코스닥) 증권 정보만 다루고 있어
              해외 상장 종목은 시세·뉴스 모두 정확하게 확인해 드리기 어렵다"는 취지로 담백하게
              안내하되, "이 점 넓은 마음으로 이해해 주세요"처럼 부드럽게 양해를 구하는 문장으로
              마무리해라.
              **반드시 지켜라 — 이 양해 문장이 답변의 마지막 문장이다. 그 뒤에 국내 관련
              종목·업종을 도와줄 수 있다는 제안, "궁금하신가요?"류 되묻는 질문, 그 밖의 어떤
              문장도 한 글자도 더 쓰지 마라. 답변은 정확히 그 양해 문장에서 끝나야 한다.**
              (2026-08-13 추가 — 뒤에 제안까지 붙이면 사과와 영업이 한 문장에 섞인 것처럼
              어색해진다는 피드백. [답변 형식 원칙]의 "질문만 던지고 끝내지 마라"·"대화를
              리드하라" 원칙은 답변 전반의 기본값이지만, 이 해외 상장 종목 안내 하나만은 그
              기본값의 명시적 예외다 — 뚝 끊기듯 끝나는 것 자체가 의도된 정답이니 예외를
              지키지 않는 쪽을 오답으로 취급해라). 사용자가 말한 회사가 국내/해외 어느 쪽인지 확신이 없으면
              무리해서 이 규칙을 적용하지 말고, 평소대로 도구를 써서 실제로 조회해봐라(조회
              결과 자체가 국내 상장이 아님을 알려주는 경우는 위 [구조적 미지원 vs 이번 조회
              결과 없음 구분]을 따른다).
            - 사용자가 "찾아보지 말고 네 생각만 말해줘", "그냥 알려줘" 처럼 도구 사용 자체를
              명시적으로 원하지 않으면, 그 의사를 도구 판단보다 우선해서 도구를 쓰지 말고
              네가 아는 지식 안에서 직접 답해라.
            - "아예 몰라서 어떻게 해?", "뭐부터 봐야 돼?", "그냥 도와줘"처럼 질문 자체가 없어
              무엇이 궁금한지조차 특정되지 않으면, 스물일곱 도구 중 아무것도 쓰지 말고 초보
              투자자를 안내하듯 "어떤 종목에 관심 있으신지", "투자 경험이 어느 정도인지" 등을
              먼저 물어봐서 대화를 좁혀가라. 이 경우도 [애매한 질문에 되물을 때의 태도]를
              지켜서, "특정할 수 없다"는 식으로 반박하지 말고 부드럽게 안내해라.
            - "지금 가격 얼마야?", "지금 얼마에 거래되고 있어?"처럼 현재가를 물으면
              get_current_price를 써라(2026-08-07 추가 — LS증권에 그때그때 직접 조회하며,
              장중이면 실시간가, 장 마감 후면 마지막 체결가를 받는다). 다만 "지금 호가가
              어떻게 돼?"처럼 매수·매도 호가창 자체를 묻는 건 이 도구로도 다루지 않으니,
              아래처럼 없는 기능을 흉내내지 말고 솔직히 안내해라.
            - "거래량 몇이야?", "오늘 얼마나 거래됐어?"도 get_current_price 결과에 "누적
              거래량" 값으로 이미 포함돼 있다(2026-08-11 추가 — 라이브 테스트에서 이 값을
              받고도 "집계 방식 차이로 정확한 수치를 안내하기 어렵다"며 얼버무리는 사례가
              확인됨). 결과에 누적 거래량이 와 있으면 그 숫자를 그대로 인용해서 답하고,
              실시간성이나 증권사별 집계 차이를 이유로 회피하지 마라 — 그 값 자체가 이미
              LS증권에서 그때그때 직접 받아온 실측치다.
            - 투자와 관련은 있지만 스물일곱 도구 중 어떤 것도 다루지 않는 요청(예: 실시간 호가창,
              또는 일반적인 증권 용어·개념 설명)이면, 없는 도구를 억지로 끼워 맞추거나
              조회하는 척 흉내내지 마라(2026-08-07 추가 — 이 경우에 [도구 호출과 함께 보내는
              텍스트]의 "실제로 실행해라" 규칙과 부딪혀 흉내내기 버그가 실제로 재발함). 이런
              요청은 이 도구들로 확인할 수 없다는 걸 자연스럽게 알리고, 대신 확인 가능한 부분
              (실시간 현재가, 재무 실적, 관련 뉴스, 공시 등)이 있다면 그건 정상적으로 도구를
              써서 답하고, 그 외 일반적인 증권 지식이나 개념 설명은 네가 아는 지식 안에서
              직접 답해라.

            1단계 — 대상 종목/회사가 특정되는가?
            - 스물일곱 도구 모두 어떤 종목/회사를 말하는지 특정할 수 없으면 쓰지 말고, 사용자에게
              먼저 되물어라(아래 [애매한 질문에 되물을 때의 태도] 준수).

            2단계 — 궁금해하는 내용이 "정성적"인가 "정량적/제도적"인가?
            - 정성적(사건이 있었는지, 시장·여론 반응, 최근 분위기, 소문의 진위, 향후 전망에
              참고할 배경) → search_securities_news.
            - 정량적/제도적(공식 수치, 법적 절차, 등록부상 사실)이면 그 중에서도 더 세분화해라:
              지금 이 순간의 거래 가격이나 PER·PBR·52주 최고/최저·상장주식수·외국인 소진율 →
              get_current_price. 실적·재무 상태 수치 → get_financial_statements. 유상증자·
              무상증자 → get_capital_change_info. 최대주주가 누구인지·바뀌었는지 →
              get_ownership_info. 그 외 공시 주제(배당, 임원현황, 회사채 발행, 소송, 합병·분할,
              부도, 자사주 취득 등, get_disclosure_info 설명에 나열된 목록 기준) →
              get_disclosure_info. 최근 외국인·기관이 순매수했는지 → get_foreign_institutional_trend.
              증권사 투자의견·목표주가 수치 → get_investment_opinion. 주주총회 날짜 →
              get_shareholder_meeting_schedule. 목록에 없는 주제면 이 도구도 쓰지 말고 확인할
              수 없는 정보라고 답해라.
            - 겹치는 주제 구분(2026-08-10 추가 — LS 도구 3개와 DART 도구가 표면적으로 비슷해
              보이는 주제를 다룰 때 반드시 이 기준으로 갈라라):
              (1) "외국인이 이 종목 사고 있어?"(그룹 전체의 최근 매매 동향) →
              get_foreign_institutional_trend. "최대주주가 누구야?"/"5% 이상 보유한 사람 바뀌었어?"
              (특정 투자자 1명의 등록부상 지위) → get_ownership_info. 둘을 혼동하지 마라 — 하나는
              "누가 대주주냐"이고 다른 하나는 "요즘 누가 사고파냐"다.
              (2) "목표주가 얼마로 올렸대?"처럼 정확한 수치가 필요하면 get_investment_opinion을
              써라. "그냥 시장 반응이 어때?"처럼 정성적 맥락만 필요하면 search_securities_news만
              써도 되지만, 사용자가 구체적 숫자(목표가·의견 변경 전후)를 원하는 뉘앙스면 뉴스만
              쓰지 말고 get_investment_opinion을 함께 요청해라.
              (3) "배당 언제 줘?"/"배당 얼마야?"는 전혀 다른 질문이다 — 날짜(개최 일정)만
              물으면 get_shareholder_meeting_schedule은 주주총회 전용이라 배당 자체는 답을
              못 준다는 점에 유의하고, 배당 "금액·배당률"이 궁금하면 반드시
              get_disclosure_info(disclosureType='배당사항')를 써라. "주주총회가 언제야?"에만
              get_shareholder_meeting_schedule을 써라.
            - "시장 전체"를 묻는 질문(특정 종목 하나가 아니라 "오늘 잘나가는 종목", "ROE 높은
              종목", "핫한 테마" 등): 등락률·시가총액·거래량·거래대금·거래급증 등 시세 기준
              순위 → get_market_ranking(rankingType으로 정확히 구분). ROE·PER·PBR 등 재무지표
              기준 순위 → get_financial_ranking. 테마 관련(테마 구성종목/종목이 속한 테마/오늘
              핫테마) → get_theme_info(mode로 구분). 다우지수·나스닥·환율 등 해외 지수 →
              get_overseas_index. 증시 대기자금(고객예탁금·신용융자잔고) 추이 →
              get_market_liquidity_trend.
            - 시간대 제한이 있는 도구: get_market_ranking의 rankingType을
              AFTER_HOURS_PRICE_CHANGE_RATE/AFTER_HOURS_VOLUME으로 요청해도, 지금이 시간외
              거래시간(15:30~18:00)이 아니면 도구 실행 결과가 "지금은 확인할 수 없다"로 온다 —
              이 경우 억지로 다른 값을 지어내지 말고 그 안내를 그대로 자연스럽게 전달해라.
            - 중요: 뉴스와 공시류 도구는 서로 배타적이지 않다. "배당 늘렸다는 얘기 있어?"처럼
              사실 확인(공시)과 배경 설명(뉴스) 둘 다에 걸치는 질문이면, 둘 중 하나만 골라
              쓰지 말고 관련된 도구를 전부 동시에 요청해라. "실적이랑 관련 뉴스 같이 알려줘"
              처럼 사용자가 여러 정보를 명시적으로 같이 요청한 경우도 동일하다.
            - 도구 요청은 한 번만 허용되므로, 하나만 먼저 써보고 결과를 본 뒤 나머지를 또
              요청하는 식으로 나누지 말고, 필요한 도구를 전부 첫 요청에 함께 담아야 한다.

            [애매한 질문에 되물을 때의 태도]
            - 사용자가 애매하게 말했다고(예: "삼성 관련주 어때요?") 해서 "특정할 수 없다",
              "말씀해주셔야 답변드릴 수 있다"처럼 반박하거나 답변을 막는 태도로 말하지 마라.
            - 먼저 그 주제에 긍정적으로 호응하고("삼성 관련주 괜찮죠!" 같은 식으로), 관련된
              간단한 정보(예: 계열사 목록, 최근 분위기)를 짧게 얹어준 뒤에, 자연스럽게 "지금 보고
              계신 건 어느 쪽이에요?" 처럼 물어봐라. 사용자가 거절당했다고 느끼면 안 된다.
            - 되묻는 답변이라도 이 뒤 [답변 형식 원칙]의 기승전결 흐름(인사/공감 → 상황 설명 →
              관련 정보 → 자연스러운 질문으로 마무리)을 그대로 따라야 한다. 대화가 뚝 끊기듯
              질문만 툭 던지고 끝내지 마라.
            - 되묻는 답변도 하나의 완전한 답변이지만, [첫 인사]에서 말했듯 인사말 자체는 네가
              붙이지 않는다 — 되묻기든 아니든 동일하다.

            [실제 데이터 예시를 요청받았을 때] (2026-08-13 추가 — "실제 공시데이터를 예시로
            들어줘봐"처럼 구체적 데이터를 보여달라는 요청에, 도구를 실제로 부르지 않고 "임원·
            주요주주소유보고 같은 게 있어요, 어떤 걸 볼까요?"처럼 개념 설명 + 되묻기로만 끝내는
            회피가 라이브 테스트로 확인됨 — [확인해줄 수 없는 요청]과 달리 이건 도구가 분명히
            있는데도 파라미터(예: disclosureType 70종 중 어떤 것)가 안 정해졌다는 이유만으로
            회피한 경우라 별도로 다룬다.)
            - "실제로", "진짜", "예시로", "직접 보여줘"처럼 구체적 데이터를 보여달라는 뉘앙스면,
              도구에 필요한 파라미터를 사용자가 안 정해줬더라도 되묻지 말고 네가 그 상황에서
              가장 흔히 궁금해할 만한 값을 하나 스스로 골라 실제로 도구를 호출해라(예:
              get_disclosure_info라면 배당사항처럼 대표성 있는 항목 하나를 골라서 실제 조회
              결과를 보여준다). 조회가 끝난 뒤에 "다른 주제도 볼 수 있다"고 자연스럽게 이어가는
              건 괜찮지만, 조회를 아예 안 하고 되묻기만 하는 건 금지다.
            - 이 원칙은 [애매한 질문에 되물을 때의 태도]와 구분해서 적용해라 — 대상 자체(회사·
              종목)가 안 정해진 경우는 되물어야 하지만(대상이 없으면 어떤 도구도 못 부름), 대상은
              이미 정해졌고 도구 파라미터 하나만 안 정해진 경우는 이 원칙대로 스스로 정해서 바로
              실행해라.

            [확인해줄 수 없는 요청을 안내하는 방식] (2026-08-11 추가 — LS API 213개 전수조사
            과정에서 "질문은 자연스러운데 신뢰할 만한 도구가 없는" 경우가 실제로 있음을 확인
            (예: 이동평균선 — LS가 제공하는 값이 조건 조합에 따라 자주 비어 신뢰도가 낮음).
            이런 요청은 아래 3단계 틀로 항상 같은 방식으로 안내해라.
            1) 인정: "그 부분은 제가 정확히 알려드리기 어려워요"처럼 먼저 솔직히 인정해라.
            2) 이유: 왜 안 되는지 구체적으로 설명해라 — 데이터 신뢰도가 낮아서, 차트처럼 눈으로
               봐야 하는 정보라 텍스트로 전달하기 어려워서, 특정 시간대에만 확인 가능해서 등.
               이유 없이 "확인할 수 없다"고만 하고 끝내지 마라.
            3) 대안 제시: 대신 확인해줄 수 있는 것이 있으면 먼저 제안해라(예: "이동평균선 대신
               지지선·저항선이나 최근 추세는 알려드릴 수 있어요, 그걸 볼까요?").
            스물일곱 도구 중 어느 것도 다루지 않는 요청 전반(실시간 호가창, 차트 패턴 분석 등)에도
            이 3단계 틀을 그대로 적용해라 — "없는 기능을 흉내내지 마라"는 기존 원칙과 이 틀은
            같은 원칙의 두 표현이다.

            [구조적 미지원 vs 이번 조회 결과 없음 구분] (2026-08-13 추가 — 실패 상황마다
            "죄송합니다"를 반복하면 오히려 부자연스럽다는 라이브 테스트 피드백 반영) 위 3단계
            틀은 "애초에 다룰 수 없는 요청"(해외 상장 종목 시세, 실시간 호가창, 차트 패턴 분석,
            이동평균선 등 — 도구 결과 자체가 "국내 상장 종목이 아니거나" 같은 구조적 사유를
            알려주는 경우)에만 써라. 이때도 "정확히 알려드리기 어려워요" 정도의 담백한 인정이면
            충분하고, "죄송합니다"·"미안합니다" 같은 사과 표현을 매번 반복할 필요는 없다 —
            [답변 형식 원칙]의 차분한 톤 원칙과 같은 이유다.
            반대로 도구는 정상적으로 지원되는데 이번 조회에서만 결과가 비어 있는 경우(예: 최근
            2년간 그런 유상증자·공시가 없었음, 관련 뉴스가 없음, 관리종목·투자경고 등 위험
            신호가 없음)는 실패가 아니라 그 자체로 유효한 답이다 — "확인할 수 없다"거나
            "죄송하다"는 표현을 쓰지 말고 "최근 2년간 그런 내역은 없네요"처럼 사실을 담담하게
            전달해라.

            [답변 형식 원칙]
            - 너는 1회성 질문에 보고서를 발급하는 분석 봇이 아니라, 옆에서 편하게 설명해주는
              친구 같은 재무설계사다. "핵심 요약 / 근거 / 실행 조언" 같은 제목이 달린 항목별
              리포트 형식으로 딱딱하게 끊어 쓰지 말고, 사람이 옆에서 말로 설명해주듯 자연스러운
              대화체 문단으로 풀어서 답해라.
            - 한 번의 답변에 모든 걸 욱여넣지 마라. 실제 재무설계사도 손님을 만나자마자 결론부터
              쏟아붓지 않고, 대화를 주고받으며 조금씩 이해시킨다. 새 주제가 나오면 먼저 핵심
              정보 한두 가지만 짧게 짚어주고, 사용자의 반응·후속 질문을 따라가며 대화를
              이어가라. 결론(실행 조언)을 낼 근거가 이미 다 갖춰졌더라도, 사용자가 아직
              충분히 이해했다고 보기 어렵거나 더 물어볼 여지가 있으면 조언부터 던지지 말고
              한두 턴 더 대화를 이어가라.
            - "기승전결"의 진짜 의미는 한 답변 안에서 (기)~(결)을 전부 완성하라는 게 아니라,
              하나의 대화 주제가 이어지는 여러 턴에 걸쳐 자연스럽게 도달하라는 뜻이다. 결론은
              사용자가 그 결론을 받아들일 준비가 됐다고 느껴질 때(충분히 정보를 나눈 뒤,
              또는 사용자가 "그래서 어떻게 하면 될까?"처럼 직접 결론을 원할 때)에만 내려라 —
              첫 답변부터 결론까지 서둘러 매듭짓지 마라. 이미 인사와 배경 설명을 마친 같은
              주제의 후속 질문(예: "그럼 순이익은요?", "그 회사 뉴스는요?")이면 인사·배경
              설명을 다시 반복하지 말고, 물어본 내용에 대한 답만 짧게 바로 이어서 답해라.
            - 길게 늘어놓는다고 더 도움이 되는 게 아니다. 문장이 길어지면 사용자가 오히려
              이해하기 어려워하니, 핵심만 간결하게 짚어라 — 재무제표 항목을 나열식으로 다
              읊거나, 이미 전한 내용을 다른 말로 반복하거나, 굳이 안 물어본 배경 설명까지
              길게 덧붙이지 마라. 한 번에 전할 답은 짧고 명확한 몇 문장이면 충분하다.
            - 결론(전달할 조언·답)이 한 번 확실하게 나왔으면 그 주제는 거기서 마무리해라.
              이미 결론까지 낸 이야기를 다른 표현으로 또 요약하거나 사족을 덧붙이지 말고,
              더 궁금한 게 있으면 사용자가 먼저 물어보게 둬라 — 대화가 스스로 끝맺어지는
              지점을 만들어야 한다. 단, 이 흐름을 헤더나 번호 목록으로 표시하지 말고
              하나의 자연스러운 대화처럼 이어써라.
            - 확정적인 수익을 보장하는 듯한 표현은 쓰지 말고, 참고용 정보임을 자연스럽게
              언급해라 — 매번 정형화된 면책 문구를 따로 떼어 붙이지 말고 설명 흐름 안에 녹여라.
            - 사용자의 투자성향에 맞춰 설명 난이도와 톤을 조절해라.
            - 톤은 친근하되 차분해야 한다(2026-08-11 추가 — 라이브 테스트에서 "영업 톤 같다",
              "너무 촐싹댄다"는 피드백을 받음). "걱정 마세요!", "아주 쉽게!"처럼 느낌표와
              과장된 감탄사를 남발하지 마라. 매 답변 끝마다 습관적으로 "~해볼까요?"를 붙이는
              것도 자제해라 — [대화 리드 원칙]은 "다음 걸음을 제안하라"는 뜻이지 "매번 텐션
              높은 영업 멘트로 끝내라"는 뜻이 아니다. 실제 재무설계사가 차분하게 설명해주는
              느낌을 유지하고, 굳이 안 물어본 것까지 신나서 더 얹지 마라.
            - "설명 감사", "고마워", "알겠어", "ㅇㅋ", "그렇구나"처럼 사용자가 대화를 마무리하려는
              신호를 보내면(2026-08-12 추가 — 라이브 테스트에서 사용자가 "설명 감사"라고
              마무리했는데도 다음 화제를 계속 제안해 대화를 억지로 이어가려는 문제가 확인됨),
              [대화 리드 원칙]을 강요하지 마라. 짧게 호응만 하고("네, 도움이 되셨다니
              다행이에요") 새 질문이나 제안을 억지로 덧붙이지 마라 — 대화는 사용자가 원할 때
              자연스럽게 끝날 수 있어야 한다.

            [대화 리드 원칙] (2026-08-07 추가 — 라이브 테스트에서 "불친절하다"는 피드백 반영)
            - 후속 질문을 마무리할 때 "어떤 쪽으로 이야기를 더 이어가 볼까요?", "무엇이
              궁금하세요?"처럼 방향 선택 자체를 사용자에게 되던지지 마라. 이미 대화에서 나온
              내용(예: 추천한 종목, 언급된 기업)을 근거로 네가 먼저 다음으로 보면 좋을 구체적인
              한 가지를 제안해라 — 예: "그럼 한화에어로스페이스 최근 실적부터 한번 볼까요?"처럼
              실행 가능한 다음 걸음을 짚어줘라. 진짜 재무설계사는 손님이 다음에 뭘 물어야 할지
              몰라 헤매게 두지 않고, 먼저 리드해서 대화를 이끈다.
            - 제안은 하되 강요는 하지 마라 — "~부터 볼까요?", "~는 어떠세요?"처럼 언제든 다른
              방향으로 바꿔도 괜찮다는 여지를 담은 어투로 제안하고, 사용자가 다른 걸 원하면
              바로 그쪽을 따라가라. 제안 자체를 생략하고 선택지만 나열하거나 질문만 던지고
              끝내는 답변은 피해라.
            - 단, "지금 가격 얼마야", "거래량 몇이야"처럼 답이 숫자 하나·사실 하나로 딱 떨어지는
              명시적 조회 질문에는 이 원칙을 강요하지 마라. 그런 질문은 물어본 값만 정확히
              답하고 끝내도 충분하다 — 매 답변 끝에 습관적으로 다음 종목·다음 화제를
              끼워 넣지 말고, 자연스럽게 이어질 만한 맥락이 있을 때만 짧게 얹어라.
            - search_securities_news, get_disclosure_info 결과에는 각 항목마다 "(링크: ...)"
              형태로 원문 URL이 함께 온다. 사용자가 따로 요청하지 않아도, 뉴스나 공시 내용을
              인용해서 답할 때는 그 링크를 답변 끝에 자연스럽게 붙여줘라(예: "자세한 내용은
              여기서 확인하실 수 있어요: (URL)"). 링크가 여러 개면 각 항목 옆에 붙이거나,
              항목이 많으면 대표적인 것 한두 개만 골라 붙여도 된다. 링크를 지어내지 말고
              결과에 실제로 담겨 온 것만 그대로 인용해라.

            [뉴스 출처/개수 관련 요청 처리] (2026-08-06 추가)
            - 사용자가 "이거 어디 기사야?", "출처가 어디야?", "그거 진짜 기사에 나온 거야?"처럼
              출처를 궁금해하면, search_securities_news 결과에 함께 담겨 온 언론사명을 그대로
              인용해서 답해라(예: "한국경제·연합뉴스 등에서 보도됐습니다"). 지어내지 말고
              실제로 결과에 담긴 언론사명만 언급해라.
            - 사용자가 "3개만", "몇 개 없어?", "더 보여줘", "최신순으로" 처럼 결과 개수나 정렬
              방식을 구체적으로 요구해도, 실제 시스템은 관련성 높은 순으로 최대 5건까지만
              고정되어 있어 그 요청을 문자 그대로 따를 수는 없다. 이 경우 요청 자체를 무시하지
              말고 "관련도 높은 순으로 몇 건 정리해 드릴게요" 처럼 자연스럽게 인지하고 있다는
              티를 내며 있는 결과 안에서 최대한 성실하게 답해라.

            [뉴스 조회 기간 한계 안내] (2026-08-11 추가 — periodDays 상한을 90일→2년으로 확장한
            결정과 짝을 이루는 규칙. 라이브 테스트에서 "몇 년 전 뉴스 찾아줘"에 그냥 "찾기
            어려웠어요"라고만 답해, 마치 넓게 찾아봤는데 우연히 안 나온 것처럼 들리는 문제가
            확인됨 — 실제로는 기간 자체가 원천적으로 범위 밖이었다는 걸 숨기고 있었다.)
            - 2년(730일)을 넘는 시점을 물었는데(예: "3년 전", "2020년에 무슨 일 있었어") 결과가
              없거나 부족하면, 그냥 "못 찾았다"고만 하지 마라. "제가 확인할 수 있는 뉴스는
              최근 2년 이내까지라, 그보다 오래된 사건은 원래 범위 밖이에요"처럼 검색 범위
              자체의 한계라는 사실을 자연스럽게 인정해라. 사용자를 탓하거나 딱딱하게 거절하지
              말고, 대신 확인 가능한 것(예: 그 회사의 최근 재무 상태나 최근 2년 내 비슷한 흐름)이
              있으면 자연스럽게 이어서 제안해라.
            - 2년 이내인데 결과가 없는 경우(예: "지난달 실적 관련 뉴스")는 이 규칙과 무관하다 —
              평소처럼 "관련성 높은 기사를 찾지 못했다"고만 정직하게 답하면 된다. 기간 자체의
              한계를 언급하는 건 정말 2년을 넘는 요청일 때만이다.
            """;

    private final AiPlanningSessionRepository sessionRepository;
    private final AiPlanningMessageRepository messageRepository;
    private final UserRepository userRepository;
    private final InvestmentProfileRepository investmentProfileRepository;
    // Account를 직접 참조하지 않고 AccountService.getMyAccounts()를 통해서만 접근한다 — 예전에는
    // AccountRepository를 직접 주입했었는데, AccountService/OrderService가 이미 같은 이유로
    // Repository 직접 참조를 제거했던 것과 동일하게 도메인 경계를 지킨다.
    private final AccountService accountService;
    // Holding/RedisStockCacheService를 직접 참조하지 않고 order 도메인이 이미 공용으로 쓰는
    // 평가 로직을 그대로 재사용한다(AccountService와 동일한 방식 — 도메인 경계 준수).
    private final HoldingValuationService holdingValuationService;
    private final RedisRateLimiterService rateLimiterService;
    private final RedisAiToolCacheService aiToolCacheService;
    private final GeminiApiClient geminiApiClient;
    private final DartApiClient dartApiClient;
    private final NaverNewsApiClient naverNewsApiClient;
    // get_current_price 도구 전용 — 회사명→stockCode(DartApiClient) 변환 후 이 클라이언트로
    // LS증권 현재가를 그때그때 REST 조회한다(LsMarketDataApiClient 클래스 주석 참고).
    private final LsMarketDataApiClient lsMarketDataApiClient;
    // get_foreign_institutional_trend 도구 전용(2026-08-10 추가) — 외국인/기관 순매수 동향.
    private final LsInvestorTrendApiClient lsInvestorTrendApiClient;
    // get_investment_opinion/get_shareholder_meeting_schedule/get_financial_ranking/
    // get_overseas_index/get_market_liquidity_trend 도구 전용(2026-08-10 3개 추가,
    // 2026-08-11 3개 추가) — 전부 같은 LS "투자정보" 카테고리(/stock/investinfo)를 공유해
    // 클라이언트 하나로 묶었다.
    private final LsInvestInfoApiClient lsInvestInfoApiClient;
    // get_market_ranking 도구 전용(2026-08-11 추가) — /stock/high-item 카테고리 7개 TR.
    private final LsHighItemApiClient lsHighItemApiClient;
    // get_theme_info 도구 전용(2026-08-11 추가) — /stock/sector 카테고리.
    private final LsSectorApiClient lsSectorApiClient;
    // get_etf_info 도구 전용(2026-08-11 추가) — /stock/etf 카테고리.
    private final LsEtfApiClient lsEtfApiClient;
    // get_program_trading_summary 도구 전용(2026-08-11 추가) — /stock/program 카테고리.
    private final LsProgramApiClient lsProgramApiClient;
    // get_investor_trend_summary 도구 전용(2026-08-11 추가) — /stock/investor 카테고리.
    private final LsInvestorApiClient lsInvestorApiClient;
    // get_stock_credit_info/get_new_listing_stocks/get_short_selling_trend/get_stock_master_info
    // 도구 전용(2026-08-11 추가) — /stock/etc 카테고리.
    private final LsEtcApiClient lsEtcApiClient;
    // get_industry_info 도구 전용(2026-08-11 추가) — /indtp/market-data 카테고리.
    private final LsIndustryApiClient lsIndustryApiClient;
    // Gemini가 한 라운드에서 여러 도구를 동시에 요청하면(parallel function calling) 이 풀로
    // 실제로 동시에 실행한다 — AsyncConfig.aiToolTaskExecutor() 참고.
    @Qualifier("aiToolTaskExecutor")
    private final Executor aiToolTaskExecutor;

    // loadHistory()/saveTurn()의 @Transactional은 Spring AOP 프록시를 거쳐야만 실제로 적용된다.
    // sendMessage()가 같은 클래스 안에서 this.loadHistory(...)/this.saveTurn(...)을 그냥 호출하면
    // (self-invocation) 프록시를 우회해 트랜잭션이 걸리지 않는다. AuthService/OrderExecutionService와
    // 동일한 패턴으로, @Lazy 필드 주입으로 받은 프록시(자기 자신)를 통해 self.xxx(...)로 호출해야 한다.
    @Autowired
    @Lazy
    private AiPlanningService self;

    @Transactional
    public AiPlanningSessionResponse createSession(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        AiPlanningSession session = AiPlanningSession.builder()
                .user(user)
                .build();
        sessionRepository.save(session);

        return AiPlanningSessionResponse.from(session);
    }

    @Transactional(readOnly = true)
    public List<AiPlanningSessionResponse> getMySessions(Long userId) {
        return sessionRepository.findAllByUserIdOrderByUpdatedAtDesc(userId).stream()
                .map(AiPlanningSessionResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AiChatResponse> getMessages(Long userId, Long sessionId) {
        AiPlanningSession session = findMySession(userId, sessionId);
        return messageRepository.findAllBySessionIdOrderByCreatedAtAsc(session.getSessionId()).stream()
                .map(AiChatResponse::from)
                .toList();
    }

    /**
     * 이 메서드에는 일부러 @Transactional을 걸지 않는다. DART/Tavily/Gemini 외부 API 호출이
     * 수 초씩 걸릴 수 있는데, 그 구간까지 하나의 DB 트랜잭션(커넥션)으로 묶으면 동시 요청이
     * 몰릴 때 커넥션 풀이 고갈되어 로그인·주문처럼 무관한 API까지 영향을 받을 수 있다. 그래서
     * self.loadHistory()의 읽기 전용 조회 → 외부 API 호출(트랜잭션 없음) → self.saveTurn()의
     * 쓰기(사용자 메시지 + AI 응답을 한 번에 저장)로 구간을 나눈다. 사용자 메시지를 Gemini 호출
     * "성공 이후"에 저장하는 이유는 saveTurn()의 주석 참고 — 실패 시 AI 응답 없는 메시지가 남는
     * 것을 막기 위함이다.
     *
     * 한 턴 안에서 converseWithTools()가 Gemini를 1번(도구 불필요) 또는 2번(도구 사용) 부를 수
     * 있지만, rate limit 카운터는 "사용자 턴" 기준으로 여기서 1번만 increment한다 — 분당3/
     * 일일10 한도는 원래 "사용자가 몇 번 물어봤는지" 기준으로 잡힌 정책이라, 내부적으로 몇 번
     * Gemini를 호출했는지는 사용자 체감 한도와 무관하게 숨긴다.
     */
    public AiChatResponse sendMessage(Long userId, Long sessionId, AiChatRequest request) {
        if (!rateLimiterService.isAllowed(userId)) {
            throw new CustomException(ErrorCode.GEMINI_RATE_LIMIT_EXCEEDED);
        }
        // RedisRateLimiterService 계약: isAllowed() 통과 "직후"에 increment한다(javadoc 참고).
        // 뒤이은 DART/Tavily/Gemini 호출까지 기다렸다가 increment하면, 한도 직전에 몰린 동시
        // 요청이 서로 increment 전에 isAllowed()를 통과해버려 한도를 우회할 수 있다.
        rateLimiterService.increment(userId);

        List<HistoryTurn> history = self.loadHistory(userId, sessionId);

        GeminiResponse geminiResponse = converseWithTools(userId, sessionId, request.content(), history);

        // 2026-08-11 추가 — 예전엔 "세션 첫 턴이면 인사해라"를 프롬프트로만 요청했는데, 라이브
        // 테스트에서 모델이 가끔 빠뜨리는 사례가 확인됐다(history가 비어 있는 게 명백한데도
        // 인사 없이 바로 본론으로 들어감). 프롬프트로 부탁하는 대신, history가 비어 있으면(=이
        // 세션의 진짜 첫 응답) 코드가 인사를 무조건 앞에 붙인다 — SYSTEM_PREAMBLE의 [첫 인사]가
        // 모델에게 "너는 인사를 직접 넣지 마라"고 지시해두었으므로 중복될 일이 없다.
        if (history.isEmpty()) {
            geminiResponse = new GeminiResponse(
                    FIRST_TURN_GREETING + geminiResponse.content(),
                    geminiResponse.tokenCount(), geminiResponse.functionCalls());
        }

        return self.saveTurn(userId, sessionId, request.content(), geminiResponse);
    }

    /**
     * "판단 로직"과 "가공 로직"을 함수 호출(Function Calling) 대화 흐름으로 처리한다. 매
     * 라운드마다 generate()를 호출해, Gemini가 도구가 필요하다고 판단하면 functionCall(들)로
     * 응답하고(텍스트 없음), 필요 없다고 판단하면(정보 불필요, 또는 종목이 애매해 되물어야 함)
     * 바로 텍스트로 응답한다 — 후자가 나오면 그 라운드에서 즉시 끝난다.
     *
     * <p>한 라운드에서 Gemini가 도구를 "여러 개 동시에" 요청할 수 있다(parallel function
     * calling) — 예를 들어 "실적이랑 관련 뉴스 같이 정리해줘"처럼 재무제표+뉴스가 모두 필요한
     * 질문이면, 순차적으로 하나씩 왕복하는 대신 한 응답에서 두 도구를 한꺼번에 요청받는다.
     * response.functionCalls()의 모든 항목을 이 라운드 안에서 함께 실행해, "필요한 도구 개수와
     * 무관하게 도구 실행 1라운드 + 최종 답변 1번"으로 끝나게 한다 — 도구를 여러 번 오가며
     * 라운드를 반복 소모하지 않기 위함이다. 마지막 라운드에는 tools를 아예 안 줘서 텍스트
     * 응답을 강제한다. SYSTEM_PREAMBLE의 "답변 형식 원칙"이 모든 라운드에 동일하게 적용되므로
     * 별도의 "가공 전용 프롬프트"를 따로 두지 않는다.</p>
     */
    private GeminiResponse converseWithTools(Long userId, Long sessionId, String userContent, List<HistoryTurn> history) {
        String prompt = buildJudgePrompt(userId, userContent);
        List<GeminiRequest.ToolDeclaration> tools = List.of(
                NEWS_SEARCH_TOOL, FINANCIALS_TOOL, CAPITAL_CHANGE_TOOL, OWNERSHIP_TOOL, DISCLOSURE_TOOL,
                CURRENT_PRICE_TOOL, FOREIGN_INSTITUTIONAL_TREND_TOOL, INVESTMENT_OPINION_TOOL,
                SHAREHOLDER_MEETING_TOOL, MARKET_RANKING_TOOL, THEME_INFO_TOOL, FINANCIAL_RANKING_TOOL,
                OVERSEAS_INDEX_TOOL, MARKET_LIQUIDITY_TOOL, TECHNICAL_SIGNAL_TOOL, HISTORICAL_PRICE_TOOL,
                MULTI_STOCK_PRICE_TOOL, RISK_FLAG_TOOL, CALL_AUCTION_PRICE_TOOL, STOCK_CREDIT_INFO_TOOL,
                ETF_INFO_TOOL, PROGRAM_TRADING_SUMMARY_TOOL, INVESTOR_TREND_SUMMARY_TOOL,
                NEW_LISTING_STOCKS_TOOL, SHORT_SELLING_TREND_TOOL, STOCK_MASTER_INFO_TOOL, INDUSTRY_INFO_TOOL);
        List<List<GeminiRequest.FunctionExchange>> exchangeRounds = new ArrayList<>();
        // executeTool()이 aiToolTaskExecutor로 동시 실행되므로 CopyOnWriteArrayList로 스레드
        // 안전하게 모은다 — describeConfirmedCurrentPrices() 참고. get_current_price와
        // get_multi_stock_price 둘 다 여기 채워 넣는다(2026-08-11 — 두 종목을 한 번에 물으면
        // Gemini가 get_multi_stock_price를 고르는데, 이 도구는 애초에 거래량을 결과 텍스트에
        // 담지 않아 물어봐도 답할 수 없는 상태였다. 아래에서 거래량을 추가하고, 가격도
        // get_current_price와 동일하게 코드가 직접 확정해서 붙인다).
        List<ConfirmedPrice> confirmedCurrentPrices = new java.util.concurrent.CopyOnWriteArrayList<>();

        for (int round = 0; round <= MAX_TOOL_CALL_ROUNDS; round++) {
            boolean isFinalRound = round == MAX_TOOL_CALL_ROUNDS;
            List<GeminiRequest.ToolDeclaration> toolsForThisRound = isFinalRound ? List.of() : tools;
            // 하이브리드 모델(2026-08-06): 도구 목록을 함께 보내는 판단 라운드는 JUDGE(flash-lite,
            // "도구 필요한가?"만 판단하면 되는 단순 작업), 도구 없이 텍스트를 강제하는 마지막
            // 라운드는 ANSWER(flash, 도구 실행 결과+포트폴리오+투자성향을 종합하는 최종 답변)를
            // 쓴다. 판단 라운드에서 도구 없이 바로 텍스트로 답하는 경우(잡담·되묻기 등)는 그
            // 자체가 "단순 작업"이라 JUDGE 모델의 답을 최종 답변으로 그대로 쓴다.
            GeminiRequest.GeminiModel model = isFinalRound ? GeminiRequest.GeminiModel.ANSWER : GeminiRequest.GeminiModel.JUDGE;
            GeminiResponse response = geminiApiClient.generate(
                    new GeminiRequest(SYSTEM_PREAMBLE, prompt, history, toolsForThisRound, exchangeRounds, model));

            if (!response.isFunctionCall()) {
                if (confirmedCurrentPrices.isEmpty()) {
                    return response;
                }
                return new GeminiResponse(
                        response.content() + describeConfirmedCurrentPrices(confirmedCurrentPrices),
                        response.tokenCount(), response.functionCalls());
            }
            // Gemini가 functionCall과 함께 "확인해볼게요" 같은 안내 텍스트를 같이 보낼 때가
            // 있는데, 최종 답변(ANSWER 라운드)이 어차피 인사·자기소개까지 포함해 완결된 답을
            // 다시 만들어내므로, 이 판단 라운드의 텍스트를 최종 답변에 이어붙이면 인사말이
            // 두 번 나오는 등 어색해진다(실제 라이브 테스트로 확인, 2026-08-06). 그래서 버리고
            // 로그만 남긴다.
            if (response.content() != null && !response.content().isBlank()) {
                log.debug("판단 라운드에서 온 안내 텍스트(최종 답변에는 미반영) - userId: {}, content: {}",
                        userId, response.content());
            }

            // executeTool()은 DART/네이버를 기다리는 블로킹 호출이라, 한 라운드에 동시 요청된
            // 도구가 여럿이면 순차 실행 시 지연시간이 그대로 합산된다 — aiToolTaskExecutor에
            // 동시에 맡겨 가장 느린 도구 하나만큼만 기다리게 한다. join()은 리스트 순서대로
            // 부르지만 그 시점엔 이미 전부 백그라운드에서 실행 중이라 결과 순서(=요청 순서)는
            // 그대로 유지된다.
            //
            // aiToolTaskExecutor(코어10/최대20/큐200)가 포화되면 supplyAsync()가
            // RejectedExecutionException을 즉시 던진다(코드리뷰 반영) — 이 시점엔 이미
            // sendMessage()에서 rateLimiterService.increment()로 이번 턴 할당량이 소모된
            // 뒤라, 여기서 예외가 그대로 위로 새어나가면 사용자는 500만 받고 대화 기록도 안
            // 남는다(위 "tools 없이 보낸 마지막 라운드" 방어와 같은 원칙). 잡아서 다른
            // 도구 실패와 동일하게 안내 문구로 이번 턴을 끝낸다.
            List<CompletableFuture<GeminiRequest.FunctionExchange>> exchangeFutures;
            try {
                exchangeFutures = response.functionCalls().stream()
                        .map(functionCall -> CompletableFuture.supplyAsync(
                                () -> new GeminiRequest.FunctionExchange(
                                        functionCall.name(), functionCall.args(), functionCall.thoughtSignature(),
                                        executeTool(sessionId, functionCall, confirmedCurrentPrices)),
                                aiToolTaskExecutor))
                        .toList();
            } catch (java.util.concurrent.RejectedExecutionException e) {
                log.warn("도구 실행 스레드풀 포화로 요청 거부 - userId: {}, sessionId: {}", userId, sessionId);
                return new GeminiResponse("죄송합니다, 지금 요청이 많이 몰려 처리하지 못했습니다 — 잠시 후 다시 시도해 주시겠어요?", null);
            }
            List<GeminiRequest.FunctionExchange> thisRoundExchanges = exchangeFutures.stream()
                    .map(CompletableFuture::join)
                    .toList();

            exchangeRounds = new ArrayList<>(exchangeRounds);
            exchangeRounds.add(thisRoundExchanges);
        }

        // tools 없이 보낸 마지막 라운드는 API 규격상 functionCall을 낼 수 없어 이 지점에 도달하지
        // 않아야 하지만, 혹시 Gemini가 규격을 어기고 functionCall만 반환하면 CustomException을
        // 던지는 대신 안전한 안내 문구로 답을 완성한다 — 이미 rateLimiterService.increment()로
        // 이번 턴의 Gemini 호출 할당량이 소모된 뒤이므로, 여기서 예외를 던지면 사용자는 응답도
        // 대화 기록도 없이 할당량만 잃는다.
        log.error("Gemini가 도구 없이도 최종 텍스트 응답을 내지 않음 - userId: {}", userId);
        return new GeminiResponse("죄송합니다, 지금은 답변을 완성하지 못했습니다 — 잠시 후 다시 한 번 질문해 주시겠어요?", null);
    }

    /**
     * Gemini가 요청한 도구를 실제로 실행한다. 검색/조회가 실패하거나 결과가 없어도 예외를 던지지
     * 않고 그 사실을 결과 문자열로 담아 Gemini에 되돌려준다 — 도구 하나가 실패했다고 상담
     * 전체가 끊기지 않고, Gemini가 "지금은 관련 정보를 찾지 못했다"고 자연스럽게 안내하게
     * 하기 위함이다. Gemini가 스키마와 다르게 args 자체를 비워 보내는 등 예상 밖의 응답을 줄
     * 가능성까지 방어하기 위해, 각 도구 실행에서 던질 수 있는 CustomException뿐 아니라 여기서
     * RuntimeException(예: args가 null이라 생기는 NPE)까지 함께 잡아 같은 원칙을 지킨다.
     *
     * <p>같은 세션에서 같은 회사·같은 조회 조건으로 도구가 다시 호출되면(예: "그럼 순이익은요?"
     * 같은 후속 질문) DART/Tavily를 또 부르지 않고 {@link RedisAiToolCacheService}에 저장해둔
     * 이전 결과를 그대로 재사용한다. 이 세션·도구·인자 조합으로 처음 조회하는 경우에만 실제
     * 실행 후 결과를 캐싱하며, 예상치 못한 오류(RuntimeException) 응답은 일시적일 수 있으므로
     * 캐싱하지 않는다 — 다음 질문에서 다시 시도할 기회를 남겨둔다.</p>
     */
    private Map<String, Object> executeTool(
            Long sessionId, GeminiResponse.FunctionCall functionCall,
            List<ConfirmedPrice> confirmedCurrentPrices) {
        // 실시간 시세는 5초마다 바뀌는 값이라, 나머지 도구(재무제표/공시/뉴스 등 세션 내내
        // 크게 안 바뀌는 데이터)와 같은 30분짜리 세션 캐시에 태우면 낡은 가격을 계속 재사용하게
        // 된다 — 그래서 공용 캐시 경로를 타지 않고 매번 새로 조회한다. get_current_price/
        // get_multi_stock_price(ConfirmedPrice 확정 패턴 적용 대상)뿐 아니라 get_call_auction_price
        // (동시호가 예상체결가 — 그 순간에만 유효)와 get_etf_info의 PRICE 모드(ETF 현재가. 반면
        // CONSTITUENTS 모드는 구성종목 비중이라 자주 안 바뀌므로 캐시 대상으로 남겨둔다)도 같은
        // 이유로 캐시를 우회해야 한다 — 이 중 하나라도 여기서 빠진 채 공용 캐시 경로만 타면, 캐시
        // 히트 시 해당 도구의 실제 조회가 아예 호출되지 않아 낡은 값이 캐시 유효시간(30분) 동안
        // 조용히 재사용된다(get_multi_stock_price에서 실제로 있었던 버그, 코드리뷰로 발견돼
        // get_etf_info/get_call_auction_price에도 같은 유형이 남아있는 걸 함께 확인해 반영).
        boolean isLivePriceTool = CURRENT_PRICE_TOOL_NAME.equals(functionCall.name())
                || MULTI_STOCK_PRICE_TOOL_NAME.equals(functionCall.name())
                || CALL_AUCTION_PRICE_TOOL_NAME.equals(functionCall.name())
                || (ETF_INFO_TOOL_NAME.equals(functionCall.name())
                        && !"CONSTITUENTS".equals(stringArg(functionCall.args(), "infoType")));
        if (isLivePriceTool) {
            try {
                return switch (functionCall.name()) {
                    case CURRENT_PRICE_TOOL_NAME -> executeCurrentPriceLookup(functionCall, confirmedCurrentPrices);
                    case MULTI_STOCK_PRICE_TOOL_NAME -> executeMultiStockPriceLookup(functionCall, confirmedCurrentPrices);
                    case CALL_AUCTION_PRICE_TOOL_NAME -> executeCallAuctionPriceLookup(functionCall);
                    default -> executeEtfInfoLookup(functionCall);
                };
            } catch (RuntimeException e) {
                log.warn("도구 실행 중 예상치 못한 오류 - name: {}, args: {}, 사유: {}",
                        functionCall.name(), functionCall.args(), e.getMessage());
                return Map.of("result", "요청을 처리하는 중 오류가 발생했습니다.");
            }
        }

        String cacheKey = buildCacheKey(functionCall);
        // Redis 조회/저장(getCachedResult/cacheResult)도 이 try 안에 포함한다 — Redis가
        // 일시적으로 불안정해도 DART/네이버 호출 실패와 동일하게 부드러운 안내 문구로 대체해야
        // 하며, 캐시 계층의 장애 때문에 대화 전체가 끊기면 안 된다.
        try {
            Optional<String> cached = aiToolCacheService.getCachedResult(sessionId, cacheKey);
            if (cached.isPresent()) {
                log.info("도구 실행 결과 캐시 재사용 - sessionId: {}, name: {}", sessionId, functionCall.name());
                return Map.of("result", cached.get());
            }

            Map<String, Object> result = switch (functionCall.name()) {
                case NEWS_SEARCH_TOOL_NAME -> executeNewsSearch(functionCall);
                case FINANCIALS_TOOL_NAME -> executeFinancialsLookup(functionCall);
                case CAPITAL_CHANGE_TOOL_NAME -> executeCapitalChangeLookup(functionCall);
                case OWNERSHIP_TOOL_NAME -> executeOwnershipLookup(functionCall);
                case DISCLOSURE_TOOL_NAME -> executeDisclosureLookup(functionCall);
                case FOREIGN_INSTITUTIONAL_TREND_TOOL_NAME -> executeForeignInstitutionalTrendLookup(functionCall);
                case INVESTMENT_OPINION_TOOL_NAME -> executeInvestmentOpinionLookup(functionCall);
                case SHAREHOLDER_MEETING_TOOL_NAME -> executeShareholderMeetingLookup(functionCall);
                case MARKET_RANKING_TOOL_NAME -> executeMarketRankingLookup(functionCall);
                case THEME_INFO_TOOL_NAME -> executeThemeInfoLookup(functionCall);
                case FINANCIAL_RANKING_TOOL_NAME -> executeFinancialRankingLookup(functionCall);
                case OVERSEAS_INDEX_TOOL_NAME -> executeOverseasIndexLookup(functionCall);
                case MARKET_LIQUIDITY_TOOL_NAME -> executeMarketLiquidityLookup(functionCall);
                case TECHNICAL_SIGNAL_TOOL_NAME -> executeTechnicalSignalLookup(functionCall);
                case HISTORICAL_PRICE_TOOL_NAME -> executeHistoricalPriceLookup(functionCall);
                case RISK_FLAG_TOOL_NAME -> executeRiskFlagLookup(functionCall);
                case STOCK_CREDIT_INFO_TOOL_NAME -> executeStockCreditInfoLookup(functionCall);
                case PROGRAM_TRADING_SUMMARY_TOOL_NAME -> executeProgramTradingSummaryLookup(functionCall);
                case INVESTOR_TREND_SUMMARY_TOOL_NAME -> executeInvestorTrendSummaryLookup(functionCall);
                case NEW_LISTING_STOCKS_TOOL_NAME -> executeNewListingStocksLookup(functionCall);
                case SHORT_SELLING_TREND_TOOL_NAME -> executeShortSellingTrendLookup(functionCall);
                case STOCK_MASTER_INFO_TOOL_NAME -> executeStockMasterInfoLookup(functionCall);
                case INDUSTRY_INFO_TOOL_NAME -> executeIndustryInfoLookup(functionCall);
                default -> {
                    log.warn("알 수 없는 도구 호출 요청 - name: {}", functionCall.name());
                    yield Map.of("result", "요청한 도구를 찾을 수 없습니다.");
                }
            };

            // errorResult()로 만들어진(외부 API 일시 장애로 내부에서 CustomException을 잡아 만든)
            // 결과는 Gemini에게는 정상 응답처럼 보이지만 캐싱 대상은 아니다 — TOOL_ERROR_MARKER가
            // 있으면 캐싱을 건너뛰고, Gemini에 보내기 전에는 마커를 떼어내 순수 결과만 남긴다.
            if (result.containsKey(TOOL_ERROR_MARKER)) {
                return Map.of("result", result.get("result"));
            }
            aiToolCacheService.cacheResult(sessionId, cacheKey, (String) result.get("result"));
            return result;
        } catch (RuntimeException e) {
            log.warn("도구 실행 중 예상치 못한 오류 - name: {}, args: {}, 사유: {}",
                    functionCall.name(), functionCall.args(), e.getMessage());
            return Map.of("result", "요청을 처리하는 중 오류가 발생했습니다.");
        }
    }

    private static final String TOOL_ERROR_MARKER = "toolError";

    // 도구 실행 중 CustomException을 내부에서 잡아 오류 안내 문구로 대신할 때 사용한다.
    // executeTool()이 이 마커를 보고 30분 캐싱을 건너뛴다 — 외부 API 장애는 일시적일 수 있으므로
    // 다음 질문에서 다시 시도할 기회를 남겨둔다.
    private Map<String, Object> errorResult(String message) {
        return Map.of("result", message, TOOL_ERROR_MARKER, Boolean.TRUE);
    }

    // 도구 이름 + 인자 전체로 캐시 키를 만든다. Map 순회 순서가 매번 같다는 보장이 없으므로
    // 키 이름 기준으로 정렬해, 같은 인자 조합이면 항상 동일한 캐시 키가 나오게 한다.
    private String buildCacheKey(GeminiResponse.FunctionCall functionCall) {
        Map<String, Object> args = functionCall.args();
        String argsPart = args == null ? "" : args.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(java.util.stream.Collectors.joining("&"));
        return functionCall.name() + "?" + argsPart;
    }

    // Gemini가 준 args에서 문자열 인자를 안전하게 꺼낸다. args 자체가 null이거나(스키마 위반) 키가
    // 없으면 null을 반환한다 — String.valueOf(map.get(key))처럼 없는 값을 문자열 "null"로
    // 둔갑시키지 않는다(그 상태로 DART 조회에 넘기면 "'null'의 DART 등록 정보를 찾지 못했습니다"
    // 같은 문구가 그대로 사용자에게 노출된다).
    private String stringArg(Map<String, Object> args, String key) {
        if (args == null) {
            return null;
        }
        Object value = args.get(key);
        return value != null ? value.toString() : null;
    }

    // periodDays처럼 정수 인자를 안전하게 꺼낸다. Jackson이 JSON 숫자를 Integer/Long/Double 중
    // 무엇으로 파싱하든 처리하고, 값이 없거나 형식이 안 맞으면 null(=NaverNewsApiClient의 기본값
    // 사용)을 반환한다.
    private Integer integerArg(Map<String, Object> args, String key) {
        if (args == null) {
            return null;
        }
        Object value = args.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private Map<String, Object> executeNewsSearch(GeminiResponse.FunctionCall functionCall) {
        String companyName = stringArg(functionCall.args(), "companyName");
        String topic = stringArg(functionCall.args(), "topic");
        Integer periodDays = integerArg(functionCall.args(), "periodDays");
        // companyName은 NEWS_SEARCH_TOOL의 필수(required) 파라미터지만, Gemini가 스키마와 다르게
        // 비워 보낼 가능성까지 방어한다 — 비워둔 채로 Tavily에 넘기면 "null 관련 뉴스..." 같은
        // 무의미한 검색어로 호출돼버린다(다른 도구들의 args 방어 원칙과 동일).
        if (companyName == null || companyName.isBlank()) {
            log.warn("뉴스검색 도구 호출에 companyName이 비어 있음 - args: {}", functionCall.args());
            return Map.of("result", "어떤 종목의 뉴스를 찾을지 확인할 수 없습니다.");
        }
        String subject = topic != null && !topic.isBlank() ? companyName + " " + topic : companyName;
        try {
            NaverNewsSearchResponse response = naverNewsApiClient.search(new NaverNewsSearchRequest(companyName, topic, periodDays));
            // NaverNewsApiClient는 회사명(+주제, 있다면)이 제목·본문 어디에도 없거나, 신뢰 매체
            // 목록 밖이거나, 요청한 기간보다 오래된 기사는 이미 다 걸러서 돌려준다 — 남은 게
            // 없으면 억지로 다른 걸 끌어다 붙이지 않고 정직하게 "찾지 못했다"고 답한다
            // (2026-08-05 확정 원칙, TavilyApiClient 시절부터 유지).
            if (response.results().isEmpty()) {
                return Map.of("result", "'%s' 관련해서는 증권 전문 매체 기준 최근 관련성 높은 기사를 찾지 못했습니다."
                        .formatted(subject));
            }
            return Map.of("result", describeNewsResults(response.results()));
        } catch (CustomException e) {
            log.warn("뉴스검색 도구 실행 실패 - companyName: {}, topic: {}, 사유: {}", companyName, topic, e.getMessage());
            return errorResult("뉴스 검색 중 오류가 발생해 최신 뉴스를 가져오지 못했습니다.");
        }
    }

    // 네이버 뉴스 검색은 Tavily의 answer(자동 종합요약) 같은 게 없어, 관련성 검증까지 끝난 기사
    // 목록(최대 5개) 자체를 재료로 정리해서 넘긴다 — Gemini가 최종 답변을 쓸 때 원문에 더 가까운
    // 자료를 직접 보고 판단할 수 있다(오늘 논의한 "Tavily 요약을 그대로 못 믿겠다"는 문제를
    // 구조적으로 없애는 방향).
    private String describeNewsResults(List<NaverNewsSearchResponse.NaverNewsResult> results) {
        // outlet(언론사명)을 함께 넘겨야 사용자가 "이거 어디 기사야?"라고 물었을 때 Gemini가
        // 최종 답변에서 실제 매체명을 인용할 수 있다(2026-08-06, 축9 "출처/신뢰성 확인 요청" 대응).
        // link도 함께 넘겨 사용자가 원문을 직접 확인할 수 있게 한다(2026-08-07 추가).
        return results.stream()
                .map(result -> "[%s, %s] %s — %s (링크: %s)".formatted(
                        result.outlet(), result.pubDate(), result.title(), result.description(), result.link()))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    // executeFinancialsLookup/executeCapitalChangeLookup/executeOwnershipLookup/executeDisclosureLookup
    // 4곳이 공유하는 골격 — 회사명 → corp_code 조회(DartApiClient.resolveCorpCodeByName())가
    // 비어있으면 목록에 없는 회사이거나 목록 다운로드 자체가 실패한 것이라 바로 "찾지 못했다"로
    // Gemini에 알리고, corpCode를 찾은 뒤의 실제 조회는 lookup에 맡긴다. resolveCorpCodeByName()은
    // 자체적으로 예외를 던지지 않도록 만들어져 있지만(DartApiClient 참고), lookup 실행까지
    // 하나의 try로 묶어 DART 조회 실패도 같은 방식(errorResult, 캐싱 제외)으로 처리한다.
    // onError는 도구별로 다른 로그 인자(period/changeType/infoType/disclosureType 등)를 그대로
    // 남길 수 있도록 호출부가 직접 채운다.
    private Map<String, Object> withResolvedCorpCode(
            String companyName, Function<String, Map<String, Object>> lookup,
            Consumer<CustomException> onError, String errorMessage) {
        // companyName은 4개 도구 모두 필수(required) 파라미터지만, executeNewsSearch()와 동일한
        // 원칙으로 Gemini가 스키마를 어기고 비워 보낼 가능성을 방어한다 — 방어하지 않으면 아래
        // "찾지 못했습니다" 문구에 "'null'"이 그대로 노출된다.
        if (companyName == null || companyName.isBlank()) {
            log.warn("DART 조회 도구 호출에 companyName이 비어 있음");
            return Map.of("result", "어떤 종목의 정보를 찾을지 확인할 수 없습니다.");
        }
        Optional<String> corpCode = dartApiClient.resolveCorpCodeByName(companyName);
        if (corpCode.isEmpty()) {
            return Map.of("result", "'%s'의 DART 등록 정보를 찾지 못했습니다 — 국내(코스피/코스닥) 상장 종목이 아니거나(해외 상장 종목 등) 회사명이 정확하지 않을 수 있습니다."
                    .formatted(companyName));
        }
        try {
            return lookup.apply(corpCode.get());
        } catch (CustomException e) {
            onError.accept(e);
            return errorResult(errorMessage);
        }
    }

    private Map<String, Object> executeFinancialsLookup(GeminiResponse.FunctionCall functionCall) {
        String companyName = stringArg(functionCall.args(), "companyName");
        String period = stringArg(functionCall.args(), "period");
        return withResolvedCorpCode(companyName, corpCode -> {
            // PERIOD_ANNUAL이 아니면(값이 비어있거나 "분기"거나 그 외 무엇이든) 분기를 기본으로
            // 삼는다 — 도구 description에 명시한 "애매하면 분기" 원칙과 동일하게, 방어적으로도
            // 최신 정보 쪽을 우선한다.
            DartFinancialResponse financials = PERIOD_ANNUAL.equals(period)
                    ? dartApiClient.getFinancials(new DartFinancialRequest(corpCode, LocalDate.now().getYear() - DART_YEAR_OFFSET))
                    : dartApiClient.getRecentQuarterlyFinancials(corpCode);
            // getFinancials()/getRecentQuarterlyFinancials()는 데이터가 없어도 예외 없이 필드가
            // 전부 null인 응답을 준다(DartApiClientTest 확인). 그대로 describeFinancials()에
            // 넘기면 "정보없음"만 여섯 번 나열된 답이 나가 사용자가 이유를 알 수 없으므로, 여기서
            // 먼저 걸러내 왜 확인이 안 되는지(비상장 등)를 담은 문장으로 대신한다.
            if (isAllFieldsNull(financials)) {
                return Map.of("result", "'%s'의 %s 재무제표 공시를 찾지 못했습니다 — 비상장이거나, 아직 해당 기간 보고서가 공시되지 않은 경우일 수 있습니다."
                        .formatted(companyName, PERIOD_ANNUAL.equals(period) ? "연간" : "분기"));
            }
            return Map.of("result", describeFinancials(financials));
        }, e -> log.warn("재무제표 조회 도구 실행 실패 - companyName: {}, period: {}, 사유: {}", companyName, period, e.getMessage()),
                "재무제표 조회 중 오류가 발생해 최신 정보를 가져오지 못했습니다.");
    }

    // 유상증자/무상증자 공시(최근 2년)를 조회한다.
    private Map<String, Object> executeCapitalChangeLookup(GeminiResponse.FunctionCall functionCall) {
        String companyName = stringArg(functionCall.args(), "companyName");
        String changeType = stringArg(functionCall.args(), "changeType");
        // CAPITAL_CHANGE_TOOL의 changeType description과 동일하게 "애매하면 유상증자"가
        // 기본값이다 — "무상증자"라고 명시했을 때만 무상증자로 취급하고, 그 외(null·빈 값·오타
        // 등 스키마 위반)는 전부 유상증자로 판단한다.
        boolean isPaidIncrease = !"무상증자".equals(changeType);
        return withResolvedCorpCode(companyName, corpCode -> {
            List<Map<String, Object>> items = dartApiClient.getCapitalChangeDecisions(
                    corpCode, isPaidIncrease ? "유상증자" : "무상증자");
            if (items.isEmpty()) {
                // 이 조회는 공시가 "안 잡히는" 게 아니라 "최근 2년간 그런 결정을 안 했다"는
                // 정상적인 결과일 수 있다 — 시스템 오류처럼 보이지 않도록 그 자체가 유효한
                // 답이라는 뉘앙스를 담는다.
                return Map.of("result", "'%s'는 최근 2년간 %s 결정 공시가 없습니다 — 해당 기간 동안 %s를 하지 않았다는 뜻입니다."
                        .formatted(companyName, isPaidIncrease ? "유상증자" : "무상증자", isPaidIncrease ? "유상증자" : "무상증자"));
            }
            return Map.of("result", describeCapitalChanges(items, isPaidIncrease));
        }, e -> log.warn("자본변동 조회 도구 실행 실패 - companyName: {}, changeType: {}, 사유: {}", companyName, changeType, e.getMessage()),
                "자본변동 공시 조회 중 오류가 발생해 최신 정보를 가져오지 못했습니다.");
    }

    private String describeCapitalChanges(List<Map<String, Object>> items, boolean isPaidIncrease) {
        return items.stream()
                .map(item -> isPaidIncrease ? describePaidCapitalIncrease(item) : describeFreeCapitalIncrease(item))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private String describePaidCapitalIncrease(Map<String, Object> item) {
        return "%s 이사회결의 유상증자: 신주 %s주(보통주), 자금조달목적(시설자금 %s원/운영자금 %s원/채무상환 %s원), 증자방식 %s".formatted(
                mapValue(item, "bddd"), mapValue(item, "nstk_ostk_cnt"),
                mapValue(item, "fdpp_fclt"), mapValue(item, "fdpp_op"), mapValue(item, "fdpp_dtrp"),
                mapValue(item, "ic_mthn"));
    }

    private String describeFreeCapitalIncrease(Map<String, Object> item) {
        return "%s 이사회결의 무상증자: 신주 %s주(보통주) 무상배정, 신주배정기준일 %s, 신주상장예정일 %s".formatted(
                mapValue(item, "bddd"), mapValue(item, "nstk_ostk_cnt"),
                mapValue(item, "nstk_asstd"), mapValue(item, "nstk_lstprd"));
    }

    // 최대주주 현황/변동현황을 조회한다. 위와 동일한 패턴.
    private Map<String, Object> executeOwnershipLookup(GeminiResponse.FunctionCall functionCall) {
        String companyName = stringArg(functionCall.args(), "companyName");
        String infoType = stringArg(functionCall.args(), "infoType");
        boolean isChangeHistory = "변동".equals(infoType);
        return withResolvedCorpCode(companyName, corpCode -> {
            List<Map<String, Object>> items = dartApiClient.getOwnershipInfo(
                    corpCode, isChangeHistory ? "변동" : "현황");
            if (items.isEmpty()) {
                return Map.of("result", "'%s'의 최대주주 %s 정보를 찾지 못했습니다."
                        .formatted(companyName, isChangeHistory ? "변동" : "현황"));
            }
            // DART는 비상장 회사에 대해서도 항목 자체(행)는 내려주되 실질 값은 전부 빈칸("-")으로
            // 채워 보낸다(예: SK쉴더스). items가 비어있지 않다고 해서 바로 정상 데이터로 오해하면
            // Gemini에 "정보없음"만 반복되는 의미 없는 문장이 넘어간다 — 왜 확인이 안 되는지
            // 이유를 담아 되돌려줘야 Gemini가 "확인 불가"로 대화를 끊지 않고 이유를 설명할 수 있다.
            //
            // 다만 실제 라이브 테스트로 확인된 사실(2026-08-05, 삼성전자 최대주주 변동현황) —
            // "변동"(이력) 조회는 상장사라도 흔히 이렇게 빈 값뿐인 행 하나만 온다. 최대주주가
            // 그 보고 기간에 안 바뀌었을 뿐인 지극히 정상적인 결과이지, 비상장이라 공시 의무가
            // 없어서가 아니다. "비상장이라서"라는 이유를 "변동"에도 그대로 붙이면 상장사에도
            // 틀린 설명을 하게 되므로, "현황"(비상장 추정)과 "변동"(변동 없음 추정)을 구분한다.
            if (dartApiClient.isBlankOfContent(items, DartApiClient.OWNERSHIP_BOILERPLATE_KEYS)) {
                String reason = isChangeHistory
                        ? "이번 보고 기간 동안 최대주주가 바뀌지 않았다는 뜻입니다"
                        : "보통 비상장 회사라 관련 공시 의무가 없는 경우입니다";
                return Map.of("result", "'%s'는 최대주주 %s 공시 항목 자체가 비어 있습니다 — %s."
                        .formatted(companyName, isChangeHistory ? "변동" : "현황", reason));
            }
            return Map.of("result", describeOwnership(items, isChangeHistory));
        }, e -> log.warn("소유권 조회 도구 실행 실패 - companyName: {}, infoType: {}, 사유: {}", companyName, infoType, e.getMessage()),
                "소유권 공시 조회 중 오류가 발생해 최신 정보를 가져오지 못했습니다.");
    }

    private String describeOwnership(List<Map<String, Object>> items, boolean isChangeHistory) {
        return items.stream()
                .map(item -> isChangeHistory ? describeOwnershipChange(item) : describeOwnershipStatus(item))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private String describeOwnershipStatus(Map<String, Object> item) {
        return "%s(%s): 기초지분율 %s%%, 기말지분율 %s%%, 기준일 %s".formatted(
                mapValue(item, "nm"), mapValue(item, "relate"),
                mapValue(item, "bsis_posesn_stock_qota_rt"), mapValue(item, "trmend_posesn_stock_qota_rt"),
                mapValue(item, "stlm_dt"));
    }

    private String describeOwnershipChange(Map<String, Object> item) {
        return "%s 최대주주 변동: %s, 지분율 %s%%, 사유 %s".formatted(
                mapValue(item, "change_on"), mapValue(item, "mxmm_shrholdr_nm"),
                mapValue(item, "qota_rt"), mapValue(item, "change_cause"));
    }

    private String mapValue(Map<String, Object> item, String key) {
        Object value = item.get(key);
        return value != null ? value.toString() : "정보없음";
    }

    // get_disclosure_info(70여 개 공시)는 종류마다 응답 필드가 다 달라서 자본변동/소유권처럼
    // 필드별 전용 문장을 만들지 않는다 — 대신 회사코드 같은 식별용 필드만 제외하고 나머지를
    // "필드명: 값" 형태로 그대로 나열한다. 필드명이 DART 원본 코드(영문 축약어)라 사람이 보기엔
    // 딱딱하지만, 이 결과는 사람이 아니라 Gemini가 받아서 자기 말로 풀어 설명하는 재료로 쓰인다.
    // boilerplate 판정 로직(어떤 필드가 식별용인지 포함)은 DartApiClient가 DART 응답 형태를
    // 아는 쪽이라 거기에 두고 여기서는 재사용만 한다(DartApiClient.isBlankOfContent() 참고).
    private Map<String, Object> executeDisclosureLookup(GeminiResponse.FunctionCall functionCall) {
        String companyName = stringArg(functionCall.args(), "companyName");
        String disclosureType = stringArg(functionCall.args(), "disclosureType");
        return withResolvedCorpCode(companyName, corpCode -> {
            List<Map<String, Object>> items = dartApiClient.getDisclosureInfo(corpCode, disclosureType);
            if (items.isEmpty()) {
                return Map.of("result", "'%s'의 '%s' 관련 공시를 찾지 못했습니다.".formatted(companyName, disclosureType));
            }
            // 최대주주 조회와 동일한 이유 — 비상장 회사는 행은 내려오지만 값이 전부 빈칸일 수 있다.
            if (dartApiClient.isBlankOfContent(items, DartApiClient.DISCLOSURE_BOILERPLATE_KEYS)) {
                return Map.of("result", "'%s'는 '%s' 관련 공시 항목 자체가 비어 있습니다 — 보통 비상장 회사라 해당 공시 의무가 없거나, 이번 보고서 기준으로는 해당 사항이 없는 경우입니다."
                        .formatted(companyName, disclosureType));
            }
            return Map.of("result", describeDisclosureItems(items));
        }, e -> log.warn("공시 조회 도구 실행 실패 - companyName: {}, disclosureType: {}, 사유: {}",
                companyName, disclosureType, e.getMessage()),
                "공시 조회 중 오류가 발생해 최신 정보를 가져오지 못했습니다.");
    }

    private String describeDisclosureItems(List<Map<String, Object>> items) {
        return items.stream()
                .map(this::describeDisclosureItem)
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private String describeDisclosureItem(Map<String, Object> item) {
        String fields = item.entrySet().stream()
                .filter(entry -> !DartApiClient.DISCLOSURE_BOILERPLATE_KEYS.contains(entry.getKey()))
                .filter(entry -> entry.getValue() != null
                        && !entry.getValue().toString().isBlank()
                        && !"-".equals(entry.getValue().toString()))
                .map(entry -> entry.getKey() + ": " + entry.getValue())
                .collect(java.util.stream.Collectors.joining(", "));
        // rcept_no는 필드 나열에서는 boilerplate라 제외하지만, 사용자가 원문 공시를 직접 확인할
        // 수 있게 DART 전자공시 뷰어 링크는 별도로 붙여준다(2026-08-07 추가).
        Object rceptNo = item.get("rcept_no");
        if (rceptNo != null && !rceptNo.toString().isBlank()) {
            fields += " (링크: https://dart.fss.or.kr/dsaf001/main.do?rcpNo=" + rceptNo + ")";
        }
        return fields;
    }

    // 회사명 → stockCode(DartApiClient) → LS증권 REST 현재가(LsMarketDataApiClient) 순으로
    // 조회한다. LS WebSocket 실시간 캐시(stock:price:{stockCode})는 subscribe()한 종목만
    // 채워지는 구조라 AI가 임의의 회사를 물어보는 상황에는 맞지 않아(2026-08-07 확인 —
    // subscribe() 호출부가 아직 어디에도 연결돼 있지 않음), 그 대신 매번 REST로 직접
    // 조회한다(LsMarketDataApiClient 참고). corp_code 캐시와 달리 비상장 회사는 stockCode
    // 자체가 없어 애초에 조회 불가능하고, 상장사라도 LS 쪽 응답이 비거나 실패하면 빈 값이
    // 돌아올 수 있다 — 두 경우 모두 예외 없이 그 사실을 답변 문구로 그대로 돌려준다(다른
    // 도구들과 동일한 원칙).
    // 2026-08-11 추가 — confirmedCurrentPrices: 라이브 테스트에서 가격/거래량처럼 절대 틀리면
    // 안 되는 숫자를 Gemini가 도구 결과를 받고도 답변 문장을 쓰며 다른 값으로 지어내는 사례가
    // 반복 확인됐다(콤마 포맷팅, "그대로 인용하라" 지시 둘 다 시도했으나 완전히는 못 막음).
    // 그래서 "모델이 문장 속에서 숫자를 다시 쓰게 맡기지 않고, 실제로 조회에 성공한 값을 코드가
    // 직접 확정 문구로 덧붙인다"는 방식으로 전환한다 — 이 리스트에 성공한 조회만 모아두면
    // converseWithTools()가 최종 답변 뒤에 그대로 붙인다(부정확한 사용자 안내를 원천 차단).
    // get_current_price/get_multi_stock_price 둘 다 여기로 결과를 모은다 — 두 도구가 서로
    // 다른 DTO(LsCurrentPriceDetailDto/LsMultiStockPriceDto)를 쓰므로, 최종 답변 뒤에 붙일
    // 확정 문구는 이 공통 타입 하나만 알면 되게 통일한다.
    private record ConfirmedPrice(String stockName, String stockCode, long price, long volume) {
    }

    private Map<String, Object> executeCurrentPriceLookup(
            GeminiResponse.FunctionCall functionCall, List<ConfirmedPrice> confirmedCurrentPrices) {
        String companyName = stringArg(functionCall.args(), "companyName");
        Optional<String> stockCode = dartApiClient.resolveStockCodeByName(companyName);
        if (stockCode.isEmpty()) {
            return Map.of("result", "'%s'의 종목코드를 찾지 못해 현재가를 확인할 수 없습니다 — 국내(코스피/코스닥) 상장 종목이 아니거나(해외 상장 종목 등) 회사명이 정확하지 않을 수 있습니다."
                    .formatted(companyName));
        }

        Optional<LsCurrentPriceDetailDto> priceLookup = lsMarketDataApiClient.getCurrentPrice(stockCode.get());
        if (priceLookup.isEmpty()) {
            return Map.of("result", "'%s'는 지금 현재가를 확인할 수 없습니다 — LS증권 시세 조회가 실패했거나 해당 종목 정보가 없을 수 있습니다."
                    .formatted(companyName));
        }
        LsCurrentPriceDetailDto price = priceLookup.get();
        confirmedCurrentPrices.add(new ConfirmedPrice(price.getStockName(), price.getStockCode(),
                price.getCurrentPrice(), price.getVolume()));
        return Map.of("result", describeCurrentPrice(price));
    }

    // 위 confirmedCurrentPrices 참고 — 최종 답변 뒤에 그대로 붙일 확정 문구를 만든다. Gemini가
    // 본문에서 이미 같은 값을 정확히 말했더라도 중복 안내를 감수하고 항상 붙인다(신뢰 가능한
    // 유일한 값은 이것뿐이라는 원칙 — 조건부로 붙이면 "본문이 맞았는지"를 다시 텍스트로
    // 판별해야 해서 오히려 더 불안정해진다).
    private String describeConfirmedCurrentPrices(List<ConfirmedPrice> confirmedCurrentPrices) {
        return "\n\n[확인된 시세] " + confirmedCurrentPrices.stream()
                .map(price -> "%s(%s) %,d원, 거래량 %,d주".formatted(
                        price.stockName(), price.stockCode(), price.price(), price.volume()))
                .collect(java.util.stream.Collectors.joining(" · "));
    }

    // per/pbr/exhratio는 LS 응답 자체에 값이 없을 수 있어(우선주 등) null이면 "정보없음"으로
    // 자연스럽게 안내한다 — nullableAmount()와 같은 원칙.
    // 2026-08-11 수정 — 가격/등락액/거래량처럼 자릿수가 큰 값을 %d(콤마 없음)로 그대로 넘기면
    // Gemini가 답변 문장을 쓰는 과정에서 자릿수를 잘못 세어 숫자를 틀리는 사례(라이브 테스트로
    // 실측: SK하이닉스 실제가 1,425,000원 → 답변에는 142,500원으로 한 자리 누락)가 확인돼,
    // 모델이 그대로 베끼기만 하면 되도록 이 시점에 콤마 구분을 미리 넣어서 넘긴다.
    // 2026-08-11 추가 수정 — 콤마 포맷팅(위 주석)만으로는 거래량이 여전히 틀리는 사례가
    // 확인됨(라이브 테스트 실측: 실제 3,682,655주 → 답변에는 2,450,100주로 완전히 다른 값을
    // 지어냄, 자릿수 오기재가 아니라 아예 다른 숫자를 창작한 경우). 다른 지표들과 한 문장에
    // 섞여 있으면 모델이 값을 베끼지 않고 재구성하는 것으로 보여, 거래량만 "반드시 이 숫자
    // 그대로" 표시를 붙여 별도로 강조한다.
    private String describeCurrentPrice(LsCurrentPriceDetailDto price) {
        return ("%s(%s) 현재가 %,d원, 전일 대비 %+,d원(%.2f%%), 누적 거래량(반드시 이 숫자를 "
                + "그대로 인용할 것, 다른 값으로 바꾸지 말 것) %,d주, PER %s, PBR %s, "
                + "52주 최고 %s원(%s), 52주 최저 %s원(%s), 상장주식수 %s천주, 외국인 보유한도 소진율 %s%%, 기준시각 %s")
                .formatted(price.getStockName(), price.getStockCode(), price.getCurrentPrice(),
                        price.getChangeAmount(), price.getChangeRate(), price.getVolume(),
                        nullableDouble(price.getPer()), nullableDouble(price.getPbr()),
                        nullableAmount(price.getHigh52w()), nullableString(price.getHigh52wDate()),
                        nullableAmount(price.getLow52w()), nullableString(price.getLow52wDate()),
                        nullableAmount(price.getListingShares()), nullableDouble(price.getForeignExhaustionRate()),
                        price.getUpdatedAt());
    }

    private String nullableDouble(Double value) {
        return value != null ? value.toString() : "정보없음";
    }

    private String nullableString(String value) {
        return value != null && !value.isBlank() ? value : "정보없음";
    }

    // executeCurrentPriceLookup/executeForeignInstitutionalTrendLookup/executeInvestmentOpinionLookup/
    // executeShareholderMeetingLookup 4곳이 공유하는 골격 — DART 조회 4곳이 공유하는
    // withResolvedCorpCode()와 동일한 이유로 통합했다. LS 조회는 corp_code가 아니라 KRX
    // stockCode로 조회하므로 DartApiClient.resolveStockCodeByName()을 쓴다는 점만 다르다.
    private Map<String, Object> withResolvedStockCode(
            String companyName, Function<String, Map<String, Object>> lookup, String notFoundMessage) {
        if (companyName == null || companyName.isBlank()) {
            log.warn("LS 조회 도구 호출에 companyName이 비어 있음");
            return Map.of("result", "어떤 종목의 정보를 찾을지 확인할 수 없습니다.");
        }
        Optional<String> stockCode = dartApiClient.resolveStockCodeByName(companyName);
        if (stockCode.isEmpty()) {
            return Map.of("result", "'%s'의 종목코드를 찾지 못해 %s 국내(코스피/코스닥) 상장 종목이 아니거나(해외 상장 종목 등) 회사명이 정확하지 않을 수 있습니다."
                    .formatted(companyName, notFoundMessage));
        }
        // withResolvedCorpCode()와 동일한 이유로 lookup 실행을 try로 감싼다 — LS 조회 자체는
        // 실패해도 예외 없이 빈 결과를 주는 경우가 많지만, 모든 LS 호출이 거치는
        // LsAccessTokenProvider는 인증 실패 시 CustomException을 던진다(코드리뷰 반영). 감싸지
        // 않으면 이 예외가 executeTool()의 최상위 catch까지 그대로 올라가 도구별 안내 문구
        // 대신 뭉뚱그린 "요청을 처리하는 중 오류가 발생했습니다"만 반환된다.
        try {
            return lookup.apply(stockCode.get());
        } catch (CustomException e) {
            log.warn("LS 조회 실패 - companyName: {}, 사유: {}", companyName, e.getMessage());
            return errorResult(notFoundMessage);
        }
    }

    // 최근 10일간 외국인/기관 순매수 동향(t1716)을 조회한다. LsInvestorTrendApiClient는 실패해도
    // 예외를 던지지 않고 빈 리스트를 주므로 별도 try/catch 없이 결과가 비었는지만 확인한다.
    private Map<String, Object> executeForeignInstitutionalTrendLookup(GeminiResponse.FunctionCall functionCall) {
        String companyName = stringArg(functionCall.args(), "companyName");
        Integer periodMonths = integerArg(functionCall.args(), "periodMonths");
        return withResolvedStockCode(companyName, stockCode -> {
            List<LsForeignInstitutionalTrendDto> items = lsInvestorTrendApiClient.getTrend(stockCode, periodMonths);
            if (items.isEmpty()) {
                return Map.of("result", "'%s'의 외국인/기관 매매동향을 확인할 수 없습니다 — LS증권 조회가 실패했거나 관련 데이터가 없을 수 있습니다."
                        .formatted(companyName));
            }
            String description = periodMonths != null && periodMonths > 0
                    ? describeForeignInstitutionalTrendSummary(items, periodMonths)
                    : describeForeignInstitutionalTrend(items);
            return Map.of("result", description);
        }, "외국인/기관 매매동향을 확인할 수 없습니다.");
    }

    private String describeForeignInstitutionalTrend(List<LsForeignInstitutionalTrendDto> items) {
        return items.stream()
                .map(item -> ("%s 종가 %,d원: 외국인 순매수 %+,d주, 기관 순매수 %+,d주, 개인 순매수 %+,d주, "
                        + "프로그램매매 거래량 %,d주, 외국인 보유한도 소진율 %s%%, 공매도 수량 %,d주")
                        .formatted(item.getDate(), item.getClosePrice(),
                                item.getForeignNetBuyKrx(), item.getInstitutionNetBuyKrx(), item.getIndividualNetBuyKrx(),
                                item.getProgramTradingVolume(), nullableDouble(item.getForeignExhaustionRate()),
                                item.getShortSellingVolume()))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    // 2026-08-13 추가 — 장기간(6개월 이상) 조회는 일별 원본을 전부 나열하면 프롬프트가 지나치게
    // 길어져서(최대 500거래일), 실제 조회된 데이터를 근거로 합계·최고/최저일만 코드가 직접
    // 계산해서 요약해준다. get_historical_price의 [확인해줄 수 없는 요청] 교훈과 같은 원칙 —
    // 실제로 조회하지 못하는 범위를 모델이 지어내지 않도록, 조회된 범위 안에서만 사실을 준다.
    private String describeForeignInstitutionalTrendSummary(List<LsForeignInstitutionalTrendDto> items, int periodMonths) {
        long foreignTotal = items.stream().mapToLong(LsForeignInstitutionalTrendDto::getForeignNetBuyKrx).sum();
        long institutionTotal = items.stream().mapToLong(LsForeignInstitutionalTrendDto::getInstitutionNetBuyKrx).sum();
        long individualTotal = items.stream().mapToLong(LsForeignInstitutionalTrendDto::getIndividualNetBuyKrx).sum();
        LsForeignInstitutionalTrendDto maxForeignDay = items.stream()
                .max(Comparator.comparingLong(LsForeignInstitutionalTrendDto::getForeignNetBuyKrx)).orElseThrow();
        LsForeignInstitutionalTrendDto minForeignDay = items.stream()
                .min(Comparator.comparingLong(LsForeignInstitutionalTrendDto::getForeignNetBuyKrx)).orElseThrow();
        return ("최근 %d개월(실제 조회된 %d거래일 기준) 누적 순매수: 외국인 %+,d주, 기관 %+,d주, "
                + "개인 %+,d주. 이 기간 중 외국인이 하루에 가장 많이 산 날은 %s(%+,d주), 가장 많이 판 날은 "
                + "%s(%+,d주)입니다.")
                .formatted(periodMonths, items.size(), foreignTotal, institutionTotal, individualTotal,
                        maxForeignDay.getDate(), maxForeignDay.getForeignNetBuyKrx(),
                        minForeignDay.getDate(), minForeignDay.getForeignNetBuyKrx());
    }

    // 증권사별 투자의견/목표주가 변경 이력(t3401)을 조회한다.
    private Map<String, Object> executeInvestmentOpinionLookup(GeminiResponse.FunctionCall functionCall) {
        String companyName = stringArg(functionCall.args(), "companyName");
        return withResolvedStockCode(companyName, stockCode -> {
            List<LsInvestmentOpinionDto> items = lsInvestInfoApiClient.getInvestmentOpinions(stockCode);
            if (items.isEmpty()) {
                return Map.of("result", "'%s'의 증권사 투자의견을 찾지 못했습니다.".formatted(companyName));
            }
            return Map.of("result", describeInvestmentOpinions(items));
        }, "투자의견을 확인할 수 없습니다.");
    }

    private String describeInvestmentOpinions(List<LsInvestmentOpinionDto> items) {
        return items.stream()
                .map(item -> "%s %s: %s → %s로 의견 변경, 목표주가 %s원 → %s원(발표일 종가 %s원)"
                        .formatted(item.getDate(), item.getSecuritiesFirm(),
                                nullableString(item.getOpinionBefore()), nullableString(item.getOpinionAfter()),
                                nullableAmount(item.getTargetPriceBefore()), nullableAmount(item.getTargetPriceAfter()),
                                nullableAmount(item.getClosePriceOnDate())))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    // 주주총회 일정(t3202, upgu=09만 필터링된 결과)을 조회한다.
    private Map<String, Object> executeShareholderMeetingLookup(GeminiResponse.FunctionCall functionCall) {
        String companyName = stringArg(functionCall.args(), "companyName");
        return withResolvedStockCode(companyName, stockCode -> {
            List<LsShareholderMeetingDto> items = lsInvestInfoApiClient.getShareholderMeetingSchedule(stockCode);
            if (items.isEmpty()) {
                return Map.of("result", "'%s'의 주주총회 일정을 찾지 못했습니다.".formatted(companyName));
            }
            return Map.of("result", describeShareholderMeetings(items));
        }, "주주총회 일정을 확인할 수 없습니다.");
    }

    private String describeShareholderMeetings(List<LsShareholderMeetingDto> items) {
        return items.stream()
                .map(item -> "%s: %s".formatted(item.getDate(), item.getEventName()))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    // rankingType → (LsHighItemApiClient 호출, 시간외 게이트 필요 여부). AFTER_HOURS 두 종류는
    // 시간외 거래시간(15:30~18:00)이 아니면 아예 LS를 호출하지 않고 미지원 매뉴얼 문구로
    // 대신한다(2026-08-11 합의 — "특정 시간대에만 유효"를 탈락 사유가 아니라 게이트 조건으로
    // 처리하기로 한 결정 반영).
    private Map<String, Object> executeMarketRankingLookup(GeminiResponse.FunctionCall functionCall) {
        String rankingType = stringArg(functionCall.args(), "rankingType");
        if (rankingType == null) {
            return Map.of("result", "어떤 기준의 순위를 볼지 확인할 수 없습니다.");
        }
        boolean isAfterHoursRanking = "AFTER_HOURS_PRICE_CHANGE_RATE".equals(rankingType)
                || "AFTER_HOURS_VOLUME".equals(rankingType);
        if (isAfterHoursRanking && !DateUtil.isAfterHoursTradingTime()) {
            return Map.of("result", "시간외 거래 순위는 시간외 거래시간(15:30~18:00)에만 확인할 수 있어요 — 지금은 그 시간대가 아니라 확인할 수 없습니다.");
        }

        List<LsRankingItemDto> items = switch (rankingType) {
            case "PRICE_CHANGE_RATE" -> lsHighItemApiClient.getTopPriceChangeRate();
            case "MARKET_CAP" -> lsHighItemApiClient.getTopMarketCap();
            case "VOLUME" -> lsHighItemApiClient.getTopVolume();
            case "TRADING_VALUE" -> lsHighItemApiClient.getTopTradingValue();
            case "VOLUME_SURGE" -> lsHighItemApiClient.getSurgingVolumeVsYesterday();
            case "AFTER_HOURS_PRICE_CHANGE_RATE" -> lsHighItemApiClient.getTopAfterHoursPriceChangeRate();
            case "AFTER_HOURS_VOLUME" -> lsHighItemApiClient.getTopAfterHoursVolume();
            default -> List.<LsRankingItemDto>of();
        };
        if (items.isEmpty()) {
            return Map.of("result", "해당 기준의 순위를 지금은 확인할 수 없습니다.");
        }
        return Map.of("result", describeRankingItems(items));
    }

    private String describeRankingItems(List<LsRankingItemDto> items) {
        return items.stream()
                .map(item -> ("%d위 %s(%s) %,d원, 전일 대비 %+,d원(%.2f%%), 거래량 %s주%s")
                        .formatted(item.getRank(), item.getStockName(), item.getStockCode(),
                                item.getPrice(), item.getChangeAmount(), item.getChangeRate(),
                                nullableAmount(item.getVolume()),
                                item.getExtraInfo() != null ? ", " + item.getExtraInfo() : ""))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private Map<String, Object> executeThemeInfoLookup(GeminiResponse.FunctionCall functionCall) {
        String mode = stringArg(functionCall.args(), "mode");
        if ("THEME_TO_STOCKS".equals(mode)) {
            String themeName = stringArg(functionCall.args(), "themeName");
            if (themeName == null || themeName.isBlank()) {
                return Map.of("result", "어떤 테마인지 확인할 수 없습니다.");
            }
            List<LsThemeConstituentDto> constituents = lsSectorApiClient.getThemeConstituentsByName(themeName);
            if (constituents.isEmpty()) {
                return Map.of("result", "'%s' 테마를 찾지 못했습니다.".formatted(themeName));
            }
            return Map.of("result", describeThemeConstituents(constituents));
        }
        if ("STOCK_TO_THEMES".equals(mode)) {
            String companyName = stringArg(functionCall.args(), "companyName");
            return withResolvedStockCode(companyName, stockCode -> {
                List<LsThemeDto> themes = lsSectorApiClient.getThemesForStock(stockCode);
                if (themes.isEmpty()) {
                    return Map.of("result", "'%s'가 속한 테마를 찾지 못했습니다.".formatted(companyName));
                }
                return Map.of("result", themes.stream().map(LsThemeDto::getThemeName)
                        .collect(java.util.stream.Collectors.joining(", ")));
            }, "테마 정보를 확인할 수 없습니다.");
        }
        if ("HOT_THEMES".equals(mode)) {
            List<LsThemeDto> hotThemes = lsSectorApiClient.getHotThemes();
            if (hotThemes.isEmpty()) {
                return Map.of("result", "오늘의 핫테마 정보를 확인할 수 없습니다.");
            }
            return Map.of("result", hotThemes.stream()
                    .map(theme -> "%s (%s)".formatted(theme.getThemeName(), theme.getStats()))
                    .collect(java.util.stream.Collectors.joining("\n")));
        }
        log.warn("get_theme_info 도구 호출에 알 수 없는 mode - mode: {}", mode);
        return Map.of("result", "요청을 이해하지 못했습니다.");
    }

    private String describeThemeConstituents(List<LsThemeConstituentDto> constituents) {
        return constituents.stream()
                .map(item -> "%s(%s) %,d원, 전일 대비 %+,d원(%.2f%%), 거래량 %s주".formatted(
                        item.getStockName(), item.getStockCode(), item.getPrice(),
                        item.getChangeAmount(), item.getChangeRate(), nullableAmount(item.getVolume())))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    // criteria(사용자가 보기 쉬운 이름) → LS gubun1 코드. LsInvestInfoApiClient.getFinancialRanking()
    // 참고.
    private static final Map<String, String> FINANCIAL_RANKING_CRITERIA_CODES = Map.ofEntries(
            Map.entry("SALES_GROWTH", "1"), Map.entry("OPERATING_INCOME_GROWTH", "2"),
            Map.entry("DEBT_RATIO", "4"), Map.entry("EPS", "6"), Map.entry("BPS", "7"),
            Map.entry("ROE", "8"), Map.entry("PER", "9"), Map.entry("PBR", "a"), Map.entry("PEG", "b"));

    private Map<String, Object> executeFinancialRankingLookup(GeminiResponse.FunctionCall functionCall) {
        String criteria = stringArg(functionCall.args(), "criteria");
        String criteriaCode = FINANCIAL_RANKING_CRITERIA_CODES.getOrDefault(criteria, "8");
        List<LsFinancialRankingDto> items = lsInvestInfoApiClient.getFinancialRanking(criteriaCode);
        if (items.isEmpty()) {
            return Map.of("result", "재무순위 정보를 지금은 확인할 수 없습니다.");
        }
        return Map.of("result", items.stream()
                .map(item -> "%d위 %s(%s) ROE %.2f%%, PER %.2f, PBR %.2f, 매출액증가율 %.2f%%".formatted(
                        item.getRank(), item.getStockName(), item.getStockCode(),
                        item.getRoe(), item.getPer(), item.getPbr(), item.getSalesGrowthRate()))
                .collect(java.util.stream.Collectors.joining("\n")));
    }

    // indexName(사용자가 보기 쉬운 이름) → (kind, symbol). LsInvestInfoApiClient.getOverseasIndex() 참고.
    private static final Map<String, String[]> OVERSEAS_INDEX_SYMBOLS = Map.of(
            "다우지수", new String[]{"S", "DJI@DJI"},
            "나스닥", new String[]{"S", "NAS@IXIC"},
            "원달러환율", new String[]{"R", "USDKRWSMBS"},
            "국제유가", new String[]{"F", "NYM@CL"});

    private Map<String, Object> executeOverseasIndexLookup(GeminiResponse.FunctionCall functionCall) {
        String indexName = stringArg(functionCall.args(), "indexName");
        String[] symbolSpec = OVERSEAS_INDEX_SYMBOLS.get(indexName);
        if (symbolSpec == null) {
            return Map.of("result", "'%s'는 확인할 수 없는 지수입니다 — 다우지수, 나스닥, 원달러환율, 국제유가만 확인 가능합니다."
                    .formatted(indexName));
        }
        Optional<LsOverseasIndexDto> index = lsInvestInfoApiClient.getOverseasIndex(symbolSpec[0], symbolSpec[1]);
        if (index.isEmpty()) {
            return Map.of("result", "'%s' 조회에 실패했습니다.".formatted(indexName));
        }
        LsOverseasIndexDto value = index.get();
        return Map.of("result", "%s(%s 기준) %.2f, 전일 대비 %+.2f(%.2f%%)".formatted(
                value.getName(), value.getDate(), value.getPrice(), value.getChangeAmount(), value.getChangeRate()));
    }

    private Map<String, Object> executeMarketLiquidityLookup(GeminiResponse.FunctionCall functionCall) {
        Integer periodMonths = integerArg(functionCall.args(), "periodMonths");
        boolean longPeriod = periodMonths != null && periodMonths > 0;
        List<LsMarketLiquidityDto> items = lsInvestInfoApiClient.getMarketLiquidityTrend(periodMonths);
        if (items.isEmpty()) {
            return Map.of("result", "증시주변자금 정보를 지금은 확인할 수 없습니다.");
        }
        if (!longPeriod) {
            return Map.of("result", items.stream()
                    .map(item -> "%s: 고객예탁금 %s백만원, 신용융자잔고 %s백만원".formatted(
                            item.getDate(), nullableAmount(item.getCustomerDepositAmount()),
                            nullableAmount(item.getMarginLoanAmount())))
                    .collect(java.util.stream.Collectors.joining("\n")));
        }
        // 2026-08-13 추가 — 장기간 조회는 일별 나열 대신 코드가 계산한 요약(최고/최저일, 최근값
        // 대비 증감)만 준다 — 500거래일치를 그대로 나열하면 토큰 낭비가 크다.
        LsMarketLiquidityDto latest = items.get(0);
        LsMarketLiquidityDto maxDeposit = items.stream()
                .filter(item -> item.getCustomerDepositAmount() != null)
                .max(Comparator.comparingLong(LsMarketLiquidityDto::getCustomerDepositAmount)).orElse(latest);
        LsMarketLiquidityDto minDeposit = items.stream()
                .filter(item -> item.getCustomerDepositAmount() != null)
                .min(Comparator.comparingLong(LsMarketLiquidityDto::getCustomerDepositAmount)).orElse(latest);
        return Map.of("result",
                ("최근 %d개월(실제 조회된 %d거래일 기준) 증시 대기자금 — 가장 최근(%s) 고객예탁금 %s백만원, "
                        + "신용융자잔고 %s백만원. 이 기간 중 고객예탁금이 가장 많았던 날은 %s(%s백만원), "
                        + "가장 적었던 날은 %s(%s백만원)입니다.")
                        .formatted(periodMonths, items.size(), latest.getDate(),
                                nullableAmount(latest.getCustomerDepositAmount()), nullableAmount(latest.getMarginLoanAmount()),
                                maxDeposit.getDate(), nullableAmount(maxDeposit.getCustomerDepositAmount()),
                                minDeposit.getDate(), nullableAmount(minDeposit.getCustomerDepositAmount())));
    }

    private Map<String, Object> executeTechnicalSignalLookup(GeminiResponse.FunctionCall functionCall) {
        String companyName = stringArg(functionCall.args(), "companyName");
        return withResolvedStockCode(companyName, stockCode -> {
            Optional<LsPivotLevelDto> pivot = lsMarketDataApiClient.getPivotLevels(stockCode);
            if (pivot.isEmpty()) {
                return Map.of("result", "'%s'의 지지선/저항선 정보를 찾지 못했습니다.".formatted(companyName));
            }
            LsPivotLevelDto p = pivot.get();
            return Map.of("result", "피봇(기준값) %s원, 1차저항 %s원, 1차지지 %s원, 2차저항 %s원, 2차지지 %s원".formatted(
                    nullableAmount(p.getPivot()), nullableAmount(p.getResistance1()), nullableAmount(p.getSupport1()),
                    nullableAmount(p.getResistance2()), nullableAmount(p.getSupport2())));
        }, "지지선/저항선을 확인할 수 없습니다.");
    }

    private Map<String, Object> executeHistoricalPriceLookup(GeminiResponse.FunctionCall functionCall) {
        String companyName = stringArg(functionCall.args(), "companyName");
        Integer periodMonths = integerArg(functionCall.args(), "periodMonths");
        boolean longPeriod = periodMonths != null && periodMonths > 0;
        return withResolvedStockCode(companyName, stockCode -> {
            List<LsHistoricalPriceDto> items = lsMarketDataApiClient.getHistoricalPrices(stockCode, periodMonths);
            if (items.isEmpty()) {
                return Map.of("result", "'%s'의 시세 흐름을 확인할 수 없습니다.".formatted(companyName));
            }
            String detail = items.stream()
                    .map(item -> "%s 종가 %s원(%.2f%%), 거래량 %s주, 시가총액 %s백만원, 외국인순매수 %+d주"
                            .formatted(item.getDate(), nullableAmount(item.getClose()), item.getChangeRate(),
                                    nullableAmount(item.getVolume()), nullableAmount(item.getMarketCap()),
                                    item.getForeignNetBuy() != null ? item.getForeignNetBuy() : 0L))
                    .collect(java.util.stream.Collectors.joining("\n"));
            // 2026-08-13 추가 — "6개월간 저점/고점" 같은 질문에 모델이 지어내는 문제가 실측돼
            // (라이브 테스트), 조회된 봉들의 실제 high/low를 코드가 직접 계산해 결과에 못박는다.
            // ConfirmedPrice 패턴과 같은 원칙 — 절대 틀리면 안 되는 숫자는 모델이 다시 계산하게
            // 두지 않는다.
            if (longPeriod) {
                LsHistoricalPriceDto highest = items.stream()
                        .filter(item -> item.getHigh() != null)
                        .max(Comparator.comparingLong(LsHistoricalPriceDto::getHigh)).orElse(null);
                LsHistoricalPriceDto lowest = items.stream()
                        .filter(item -> item.getLow() != null)
                        .min(Comparator.comparingLong(LsHistoricalPriceDto::getLow)).orElse(null);
                String summary = highest != null && lowest != null
                        ? "\n\n[기간 내 실측 최고/최저] 최근 %d개월(실제 조회된 %d개 구간, 월봉 기준) 중 최고가 %,d원(%s), 최저가 %,d원(%s)."
                                .formatted(periodMonths, items.size(), highest.getHigh(), highest.getDate(),
                                        lowest.getLow(), lowest.getDate())
                        : "";
                detail = detail + summary;
            }
            return Map.of("result", detail);
        }, "시세 흐름을 확인할 수 없습니다.");
    }

    // 2026-08-11 수정 — 원래 이 도구는 결과 텍스트에 거래량을 아예 담지 않아, Gemini가 두
    // 종목 이상을 한 번에 물어봐 이 도구를 고르면 거래량 자체를 알 방법이 없었다(라이브
    // 테스트로 확인 — "거래량 몇이야"에 회피하거나 지어낸 값을 답한 원인이 실은 이거였다).
    // 거래량을 결과에 추가하고, 가격/거래량은 get_current_price와 동일하게 confirmedCurrentPrices에
    // 모아 코드가 최종 답변 뒤에 직접 확정해서 붙인다.
    private Map<String, Object> executeMultiStockPriceLookup(
            GeminiResponse.FunctionCall functionCall, List<ConfirmedPrice> confirmedCurrentPrices) {
        String companyNamesArg = stringArg(functionCall.args(), "companyNames");
        if (companyNamesArg == null || companyNamesArg.isBlank()) {
            return Map.of("result", "어떤 종목들의 현재가를 볼지 확인할 수 없습니다.");
        }
        List<String> stockCodes = java.util.Arrays.stream(companyNamesArg.split(","))
                .map(String::trim)
                .filter(name -> !name.isBlank())
                .map(dartApiClient::resolveStockCodeByName)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .toList();
        if (stockCodes.isEmpty()) {
            return Map.of("result", "요청하신 종목들의 종목코드를 찾지 못했습니다 — 국내(코스피/코스닥) 상장 종목이 아니거나(해외 상장 종목 등) 회사명이 정확하지 않을 수 있습니다.");
        }
        List<LsMultiStockPriceDto> items = lsMarketDataApiClient.getMultiStockPrices(stockCodes);
        if (items.isEmpty()) {
            return Map.of("result", "현재가를 확인할 수 없습니다.");
        }
        for (LsMultiStockPriceDto item : items) {
            if (item.getPrice() != null && item.getVolume() != null) {
                confirmedCurrentPrices.add(new ConfirmedPrice(
                        item.getStockName(), item.getStockCode(), item.getPrice(), item.getVolume()));
            }
        }
        return Map.of("result", items.stream()
                .map(item -> "%s(%s) %s원, 전일 대비 %+,d원(%.2f%%), 거래량 %s주".formatted(
                        item.getStockName(), item.getStockCode(), nullableAmount(item.getPrice()),
                        item.getChangeAmount() != null ? item.getChangeAmount() : 0L, item.getChangeRate(),
                        nullableAmount(item.getVolume())))
                .collect(java.util.stream.Collectors.joining("\n")));
    }

    private Map<String, Object> executeRiskFlagLookup(GeminiResponse.FunctionCall functionCall) {
        String companyName = stringArg(functionCall.args(), "companyName");
        return withResolvedStockCode(companyName, stockCode -> {
            List<LsStockRiskFlagDto> flags = lsMarketDataApiClient.getRiskFlags(stockCode);
            if (flags.isEmpty()) {
                return Map.of("result", "'%s'는 관리종목/투자경고/매매정지 등 위험 신호가 없습니다.".formatted(companyName));
            }
            return Map.of("result", flags.stream()
                    .map(flag -> "%s (%s, 사유코드 %s)".formatted(flag.getFlagType(), flag.getDate(), flag.getReasonCode()))
                    .collect(java.util.stream.Collectors.joining("\n")));
        }, "위험 신호 여부를 확인할 수 없습니다.");
    }

    private Map<String, Object> executeCallAuctionPriceLookup(GeminiResponse.FunctionCall functionCall) {
        if (!DateUtil.isCallAuctionTime()) {
            return Map.of("result", "동시호가 예상체결가는 동시호가 시간대(08:30~09:00 또는 15:20~15:30)에만 확인할 수 있어요 — 지금은 그 시간대가 아니라 확인할 수 없습니다.");
        }
        String companyName = stringArg(functionCall.args(), "companyName");
        return withResolvedStockCode(companyName, stockCode -> {
            List<LsCallAuctionPriceDto> items = lsMarketDataApiClient.getRecentCallAuctionPrices(stockCode);
            if (items.isEmpty()) {
                return Map.of("result", "'%s'의 동시호가 예상체결가를 확인할 수 없습니다.".formatted(companyName));
            }
            return Map.of("result", items.stream()
                    .map(item -> "%s: 예상가 %s원(%.2f%%), 예상체결량 %s주".formatted(
                            item.getTime(), nullableAmount(item.getPrice()), item.getChangeRate(),
                            nullableAmount(item.getExpectedVolume())))
                    .collect(java.util.stream.Collectors.joining("\n")));
        }, "동시호가 예상체결가를 확인할 수 없습니다.");
    }

    private Map<String, Object> executeStockCreditInfoLookup(GeminiResponse.FunctionCall functionCall) {
        String companyName = stringArg(functionCall.args(), "companyName");
        String infoType = stringArg(functionCall.args(), "infoType");
        Integer periodMonths = integerArg(functionCall.args(), "periodMonths");
        return withResolvedStockCode(companyName, stockCode -> {
            Optional<LsStockCreditInfoDto> info = switch (infoType != null ? infoType : "") {
                case "COLLATERAL_LOAN" -> lsEtcApiClient.getCollateralLoanEligibility(stockCode);
                case "MARGIN_REQUIREMENT" -> lsEtcApiClient.getMarginRequirement(stockCode);
                // MARGIN_TRADING(t1921)은 LS API 자체에 기간 지정 파라미터가 없어(연속조회
                // 커서만 지원) periodMonths를 못 받는다 — 2026-08-13 전수조사로 확인.
                case "MARGIN_TRADING" -> lsEtcApiClient.getMarginTradingTrend(stockCode);
                case "SECURITIES_LENDING" -> lsEtcApiClient.getSecuritiesLendingTrend(stockCode, periodMonths);
                default -> Optional.<LsStockCreditInfoDto>empty();
            };
            if (info.isEmpty()) {
                return Map.of("result", "'%s'의 해당 정보를 확인할 수 없습니다.".formatted(companyName));
            }
            return Map.of("result", info.get().getDetail());
        }, "신용/담보 정보를 확인할 수 없습니다.");
    }

    private Map<String, Object> executeEtfInfoLookup(GeminiResponse.FunctionCall functionCall) {
        String companyName = stringArg(functionCall.args(), "companyName");
        String infoType = stringArg(functionCall.args(), "infoType");
        return withResolvedStockCode(companyName, stockCode -> {
            if ("CONSTITUENTS".equals(infoType)) {
                List<LsEtfConstituentDto> constituents = lsEtfApiClient.getConstituents(stockCode);
                if (constituents.isEmpty()) {
                    return Map.of("result", "'%s'의 구성종목 정보를 찾지 못했습니다.".formatted(companyName));
                }
                return Map.of("result", constituents.stream()
                        .map(item -> "%s(%s) 비중 %.2f%%".formatted(item.getStockName(), item.getStockCode(), item.getWeight()))
                        .collect(java.util.stream.Collectors.joining("\n")));
            }
            Optional<LsCurrentPriceDetailDto> price = lsEtfApiClient.getCurrentPrice(stockCode);
            if (price.isEmpty()) {
                return Map.of("result", "'%s'의 현재가를 확인할 수 없습니다.".formatted(companyName));
            }
            return Map.of("result", describeCurrentPrice(price.get()));
        }, "ETF 정보를 확인할 수 없습니다.");
    }

    private Map<String, Object> executeProgramTradingSummaryLookup(GeminiResponse.FunctionCall functionCall) {
        String mode = stringArg(functionCall.args(), "mode");
        if ("TOP_STOCKS".equals(mode)) {
            List<LsProgramTradingRankDto> items = lsProgramApiClient.getTopProgramTradingStocks();
            if (items.isEmpty()) {
                return Map.of("result", "프로그램매매 상위 종목 정보를 확인할 수 없습니다.");
            }
            return Map.of("result", items.stream()
                    .map(item -> "%d위 %s(%s) 순매수대금 %s백만원".formatted(
                            item.getRank(), item.getStockName(), item.getStockCode(), nullableAmount(item.getNetBuyValue())))
                    .collect(java.util.stream.Collectors.joining("\n")));
        }
        Optional<LsProgramTradingSnapshotDto> snapshot = lsProgramApiClient.getMarketSnapshot();
        if (snapshot.isEmpty()) {
            return Map.of("result", "시장 전체 프로그램매매 정보를 확인할 수 없습니다.");
        }
        LsProgramTradingSnapshotDto s = snapshot.get();
        return Map.of("result", "매도대금 %s백만원, 매수대금 %s백만원, 순매수대금 %s백만원".formatted(
                nullableAmount(s.getOfferValue()), nullableAmount(s.getBidValue()), nullableAmount(s.getNetValue())));
    }

    private Map<String, Object> executeInvestorTrendSummaryLookup(GeminiResponse.FunctionCall functionCall) {
        String mode = stringArg(functionCall.args(), "mode");
        if ("BY_MARKET".equals(mode)) {
            List<LsMarketInvestorComparisonDto> items = lsInvestorApiClient.getMarketComparison();
            if (items.isEmpty()) {
                return Map.of("result", "시장별 투자자 동향을 확인할 수 없습니다.");
            }
            return Map.of("result", items.stream()
                    .map(item -> "%s: 개인 %+d, 외국인 %+d, 기관 %+d".formatted(
                            item.getMarketName(),
                            item.getIndividualNetBuy() != null ? item.getIndividualNetBuy() : 0L,
                            item.getForeignNetBuy() != null ? item.getForeignNetBuy() : 0L,
                            item.getInstitutionNetBuy() != null ? item.getInstitutionNetBuy() : 0L))
                    .collect(java.util.stream.Collectors.joining("\n")));
        }
        Optional<LsInvestorTypeSummaryDto> summary = lsInvestorApiClient.getInvestorTypeSummary();
        if (summary.isEmpty()) {
            return Map.of("result", "투자자유형별 동향을 확인할 수 없습니다.");
        }
        LsInvestorTypeSummaryDto s = summary.get();
        return Map.of("result", "개인 순매수 %s주, 외국인 순매수 %s주, 기관 순매수 %s주".formatted(
                nullableAmount(s.getIndividualNetBuy()), nullableAmount(s.getForeignNetBuy()), nullableAmount(s.getInstitutionNetBuy())));
    }

    private Map<String, Object> executeNewListingStocksLookup(GeminiResponse.FunctionCall functionCall) {
        Integer periodMonths = integerArg(functionCall.args(), "periodMonths");
        List<LsNewListingDto> items = lsEtcApiClient.getNewListings(periodMonths);
        if (items.isEmpty()) {
            return Map.of("result", "신규상장 종목 정보를 확인할 수 없습니다.");
        }
        return Map.of("result", items.stream()
                .map(item -> "%s(%s) 상장일 %s".formatted(item.getStockName(), item.getStockCode(), item.getListedDate()))
                .collect(java.util.stream.Collectors.joining("\n")));
    }

    private Map<String, Object> executeShortSellingTrendLookup(GeminiResponse.FunctionCall functionCall) {
        String companyName = stringArg(functionCall.args(), "companyName");
        Integer periodMonths = integerArg(functionCall.args(), "periodMonths");
        boolean longPeriod = periodMonths != null && periodMonths > 0;
        return withResolvedStockCode(companyName, stockCode -> {
            List<LsShortSellingTrendDto> items = lsEtcApiClient.getShortSellingTrend(stockCode, periodMonths);
            if (items.isEmpty()) {
                return Map.of("result", "'%s'의 공매도 정보를 확인할 수 없습니다.".formatted(companyName));
            }
            if (!longPeriod) {
                return Map.of("result", items.stream()
                        .map(item -> "%s: 공매도거래량 %s주, 비중 %.2f%%".formatted(
                                item.getDate(), nullableAmount(item.getShortSellingVolume()), item.getShortSellingRatio()))
                        .collect(java.util.stream.Collectors.joining("\n")));
            }
            // 2026-08-13 추가 — 장기간 조회는 일별 나열 대신 합계·최고일만 코드가 계산해서 준다.
            long totalVolume = items.stream()
                    .filter(item -> item.getShortSellingVolume() != null)
                    .mapToLong(LsShortSellingTrendDto::getShortSellingVolume).sum();
            LsShortSellingTrendDto peakDay = items.stream()
                    .filter(item -> item.getShortSellingVolume() != null)
                    .max(Comparator.comparingLong(LsShortSellingTrendDto::getShortSellingVolume)).orElse(items.get(0));
            return Map.of("result",
                    ("'%s'의 최근 %d개월(실제 조회된 %d거래일 기준) 누적 공매도 거래량은 %,d주입니다. "
                            + "공매도가 가장 많았던 날은 %s(%s주, 비중 %.2f%%)입니다.")
                            .formatted(companyName, periodMonths, items.size(), totalVolume,
                                    peakDay.getDate(), nullableAmount(peakDay.getShortSellingVolume()), peakDay.getShortSellingRatio()));
        }, "공매도 정보를 확인할 수 없습니다.");
    }

    private Map<String, Object> executeStockMasterInfoLookup(GeminiResponse.FunctionCall functionCall) {
        String companyName = stringArg(functionCall.args(), "companyName");
        return withResolvedStockCode(companyName, stockCode -> {
            Optional<LsStockMasterInfoDto> info = lsEtcApiClient.getStockMasterInfo(stockCode);
            if (info.isEmpty()) {
                return Map.of("result", "'%s'의 종목 기본정보를 확인할 수 없습니다.".formatted(companyName));
            }
            LsStockMasterInfoDto m = info.get();
            return Map.of("result", "상한가 %s원, 하한가 %s원, 스팩 여부: %s".formatted(
                    nullableAmount(m.getUpperLimitPrice()), nullableAmount(m.getLowerLimitPrice()),
                    m.isSpac() ? "예" : "아니오"));
        }, "종목 기본정보를 확인할 수 없습니다.");
    }

    private Map<String, Object> executeIndustryInfoLookup(GeminiResponse.FunctionCall functionCall) {
        String marketName = stringArg(functionCall.args(), "marketName");
        String mode = stringArg(functionCall.args(), "mode");
        String resolvedMarket = marketName != null ? marketName : "코스피";

        if ("TREND".equals(mode)) {
            Integer periodMonths = integerArg(functionCall.args(), "periodMonths");
            boolean longPeriod = periodMonths != null && periodMonths > 0;
            List<LsIndustryTrendDto> items = lsIndustryApiClient.getTrend(resolvedMarket, periodMonths);
            if (items.isEmpty()) {
                return Map.of("result", "%s 지수 흐름을 확인할 수 없습니다.".formatted(resolvedMarket));
            }
            String detail = items.stream()
                    .map(item -> "%s: %.2f(%.2f%%)".formatted(item.getDate(), item.getIndexValue(), item.getChangeRate()))
                    .collect(java.util.stream.Collectors.joining("\n"));
            // 2026-08-13 추가 — 장기간(월봉) 조회 시 실제 조회된 월별 종가 기준 최고/최저를
            // 코드가 직접 계산해 덧붙인다(get_historical_price와 동일한 원칙).
            if (longPeriod) {
                LsIndustryTrendDto highest = items.stream()
                        .max(Comparator.comparingDouble(LsIndustryTrendDto::getIndexValue)).orElse(null);
                LsIndustryTrendDto lowest = items.stream()
                        .min(Comparator.comparingDouble(LsIndustryTrendDto::getIndexValue)).orElse(null);
                if (highest != null && lowest != null) {
                    detail += "\n\n[기간 내 실측 최고/최저] 최근 %d개월(실제 조회된 %d개 구간, 월봉 기준) 중 최고 %.2f(%s), 최저 %.2f(%s)."
                            .formatted(periodMonths, items.size(), highest.getIndexValue(), highest.getDate(),
                                    lowest.getIndexValue(), lowest.getDate());
                }
            }
            return Map.of("result", detail);
        }
        if ("EXPECTED".equals(mode)) {
            if (!DateUtil.isCallAuctionTime()) {
                return Map.of("result", "예상지수는 동시호가 시간대(08:30~09:00 또는 15:20~15:30)에만 확인할 수 있어요 — 지금은 그 시간대가 아니라 확인할 수 없습니다.");
            }
            String session = stringArg(functionCall.args(), "callAuctionSession");
            Optional<LsExpectedIndexDto> expected = lsIndustryApiClient.getExpectedIndex(resolvedMarket, session);
            if (expected.isEmpty()) {
                return Map.of("result", "%s 예상지수를 확인할 수 없습니다.".formatted(resolvedMarket));
            }
            LsExpectedIndexDto e = expected.get();
            return Map.of("result", "예상지수 %.2f(%.2f%%), 상한가 %d종목, 하한가 %d종목".formatted(
                    e.getExpectedIndexValue(), e.getChangeRate(), e.getUpperLimitStockCount(), e.getLowerLimitStockCount()));
        }
        Optional<LsIndustryPriceDto> current = lsIndustryApiClient.getCurrentPrice(resolvedMarket);
        if (current.isEmpty()) {
            return Map.of("result", "%s 지수를 확인할 수 없습니다.".formatted(resolvedMarket));
        }
        LsIndustryPriceDto c = current.get();
        return Map.of("result", "%s 지수 %.2f(%.2f%%)".formatted(c.getIndustryName(), c.getIndexValue(), c.getChangeRate()));
    }

    /**
     * 세션 소유권을 확인하고, Gemini에 보낼 history(이번 턴 이전까지의 대화)를 읽기 전용으로
     * 조회한다. 이 시점에는 아직 아무것도 쓰지 않으므로 세션이 실제로 이 유저의 것인지만
     * 확인하면 된다. sendMessage()가 self 프록시를 통해서만 호출해야 @Transactional이 적용된다.
     */
    @Transactional(readOnly = true)
    public List<HistoryTurn> loadHistory(Long userId, Long sessionId) {
        sessionRepository.findByUserIdAndSessionId(userId, sessionId)
                .orElseThrow(() -> new CustomException(ErrorCode.AI_SESSION_NOT_FOUND));

        return buildHistory(sessionId);
    }

    /**
     * Gemini 호출이 성공한 뒤에만 호출된다. 사용자 메시지와 AI 응답을 한 트랜잭션에서 함께
     * 저장해, 중간에 실패해도 "AI 응답 없는 사용자 메시지"가 남는 일이 없게 한다. sendMessage()가
     * self 프록시를 통해서만 호출해야 @Transactional이 적용된다.
     */
    @Transactional
    public AiChatResponse saveTurn(Long userId, Long sessionId, String userContent, GeminiResponse geminiResponse) {
        AiPlanningSession session = sessionRepository.findByUserIdAndSessionId(userId, sessionId)
                .orElseThrow(() -> new CustomException(ErrorCode.AI_SESSION_NOT_FOUND));

        AiPlanningMessage userMessage = AiPlanningMessage.builder()
                .session(session)
                .role(MessageRole.USER)
                .content(userContent)
                .build();
        messageRepository.save(userMessage);

        if (session.getTitle() == null) {
            session.updateTitle(truncateForTitle(userContent));
        }
        session.recordActivity();

        AiPlanningMessage aiMessage = AiPlanningMessage.builder()
                .session(session)
                .role(MessageRole.AI)
                .content(geminiResponse.content())
                .promptTokens(geminiResponse.tokenCount())
                .build();
        messageRepository.save(aiMessage);

        return AiChatResponse.from(aiMessage);
    }

    private AiPlanningSession findMySession(Long userId, Long sessionId) {
        return sessionRepository.findByUserIdAndSessionId(userId, sessionId)
                .orElseThrow(() -> new CustomException(ErrorCode.AI_SESSION_NOT_FOUND));
    }

    private List<HistoryTurn> buildHistory(Long sessionId) {
        // 세션 전체 메시지를 다 읽어와 Java에서 최근 N개만 자르면 대화가 길어질수록 매 턴마다
        // 불필요한 조회량이 계속 늘어난다 — DB 레벨에서 최신 N개(내림차순)만 가져온 뒤 대화
        // 순서(오름차순)로 뒤집어 쓴다.
        List<AiPlanningMessage> recentMessagesDesc =
                messageRepository.findRecentBySessionId(sessionId, Pageable.ofSize(MAX_HISTORY_MESSAGES));

        return recentMessagesDesc.reversed().stream()
                .map(message -> new HistoryTurn(toGeminiRole(message.getRole()), message.getContent()))
                .toList();
    }

    private String toGeminiRole(MessageRole role) {
        return role == MessageRole.USER ? "user" : "model";
    }

    // String.substring()은 UTF-16 코드 유닛 기준으로 잘라, 이모지 같은 서로게이트 쌍 문자의
    // 중간을 자르면 깨진(단독 서로게이트) 문자열이 만들어질 수 있다. offsetByCodePoints로
    // "문자(코드포인트) 단위"로 자를 위치를 구해 항상 온전한 문자 경계에서 자른다.
    private String truncateForTitle(String content) {
        String trimmed = content.trim();
        if (trimmed.codePointCount(0, trimmed.length()) <= TITLE_MAX_LENGTH) {
            return trimmed;
        }
        int cutIndex = trimmed.offsetByCodePoints(0, TITLE_MAX_LENGTH);
        return trimmed.substring(0, cutIndex);
    }

    /**
     * Gemini가 "판단"하는 데 필요한 고정 컨텍스트(투자정보 + 보유종목)를 조합해 프롬프트를
     * 만든다. 뉴스와 재무제표 모두 여기서 미리 가져오지 않는다 — 예전에는 사용자 문장 원문(또는
     * 거기에 보유종목명을 억지로 붙인 값)을 그대로 Tavily 검색어로 써서, "삼성증권을 물었는데
     * 보유종목인 삼성전자로 검색되는" 식의 오염이 있었고, DART도 사용자 질문과 무관하게 항상
     * 대표 보유종목 하나만 조회하는 비대칭 구조였다. 이제는 뉴스·재무제표 모두 NEWS_SEARCH_TOOL/
     * FINANCIALS_TOOL을 통해 Gemini가 이 컨텍스트와 사용자 질문을 보고 스스로 필요 여부와
     * 대상 회사를 판단한다.
     */
    private String buildJudgePrompt(Long userId, String userContent) {
        StringBuilder promptBuilder = new StringBuilder();

        investmentProfileRepository.findByUserId(userId).ifPresent(profile ->
                promptBuilder.append("[사용자 투자성향]\n").append(describeProfile(profile)).append('\n'));

        findPrimaryHolding(userId).ifPresent(holding ->
                promptBuilder.append("[보유종목]\n").append(describeHolding(holding)).append('\n'));

        promptBuilder.append(planningPreferencesService.describeConnections(userId));
        promptBuilder.append("[사용자 질문]\n").append(userContent);
        return promptBuilder.toString();
    }

    private String describeProfile(InvestmentProfile profile) {
        return "투자성향 점수 %d, 자금성향 점수 %d, 투자 레벨 %s".formatted(
                profile.getInvestmentTendency(), profile.getFundTendency(), profile.getInvestmentLevel());
    }

    private String describeHolding(HoldingValuationDto holding) {
        return "%s(%s) %,d주, 평단가 %,d원, 평가금액 %,d원".formatted(
                holding.stockName(), holding.stockCode(), holding.quantity(),
                holding.avgPrice(), holding.currentPrice() * holding.quantity());
    }

    private String describeFinancials(DartFinancialResponse financials) {
        return "%d년 매출액 %s원, 영업이익 %s원, 당기순이익 %s원, 자산총계 %s원, 부채총계 %s원, 자본총계 %s원".formatted(
                financials.bizYear(),
                nullableAmount(financials.revenue()),
                nullableAmount(financials.operatingProfit()),
                nullableAmount(financials.netIncome()),
                nullableAmount(financials.totalAssets()),
                nullableAmount(financials.totalLiabilities()),
                nullableAmount(financials.totalEquity()));
    }

    // 2026-08-11 수정 — describeCurrentPrice()와 동일한 이유로, 이 헬퍼를 함께 쓰는 다른
    // describe*() 메서드(52주 최고/최저, 목표가, 상장주식수 등)에서도 큰 숫자를 콤마 없이
    // 넘기면 Gemini가 옮겨 적으며 자릿수를 틀릴 수 있어 여기서도 콤마 구분을 넣는다.
    private String nullableAmount(Long amount) {
        return amount != null ? "%,d".formatted(amount) : "정보없음";
    }

    // 6개 계정이 전부 null이면 CFS/OFS 둘 다 데이터를 못 찾은 것(DartApiClient.getFinancials()
    // 참고) — describeFinancials()로 "정보없음"만 여섯 번 나열하지 않고 이유를 담은 문장으로
    // 대신 처리하기 위한 판정.
    private boolean isAllFieldsNull(DartFinancialResponse financials) {
        return financials.revenue() == null
                && financials.operatingProfit() == null
                && financials.netIncome() == null
                && financials.totalAssets() == null
                && financials.totalLiabilities() == null
                && financials.totalEquity() == null;
    }

    // 유저의 첫 번째 계좌 기준으로 평가금액(현재가×수량)이 가장 큰 보유종목 1개를 고른다.
    // 계좌가 여러 개여도 상담 프롬프트에는 대표 종목 하나만 있으면 충분하다.
    private Optional<HoldingValuationDto> findPrimaryHolding(Long userId) {
        List<AccountInfoResponse> accounts = accountService.getMyAccounts(userId);
        if (accounts.isEmpty()) {
            return Optional.empty();
        }

        List<HoldingValuationDto> valuations = holdingValuationService.getHoldingValuations(accounts.get(0).accountId());
        return valuations.stream()
                .max(Comparator.comparingLong(v -> v.currentPrice() * v.quantity()));
    }

}
