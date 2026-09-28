package com.teamfp.aistock.domain.ai.service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import com.teamfp.aistock.domain.ai.dto.NewsSourceLinkDto;
import com.teamfp.aistock.domain.ai.dto.response.NewsBriefingResponse;
import com.teamfp.aistock.domain.ai.dto.response.NewsBriefingSettingResponse;
import com.teamfp.aistock.domain.ai.dto.response.NewsOutletResponse;
import com.teamfp.aistock.domain.ai.entity.NewsBriefing;
import com.teamfp.aistock.domain.ai.entity.NewsBriefingSetting;
import com.teamfp.aistock.domain.ai.repository.NewsBriefingRepository;
import com.teamfp.aistock.domain.ai.repository.NewsBriefingSettingRepository;
import com.teamfp.aistock.domain.notification.entity.NotificationType;
import com.teamfp.aistock.domain.notification.service.NotificationService;
import com.teamfp.aistock.domain.user.entity.User;
import com.teamfp.aistock.domain.user.repository.UserRepository;
import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;
import com.teamfp.aistock.global.util.NewsRelevanceMatcher;
import com.teamfp.aistock.infra.gemini.GeminiApiClient;
import com.teamfp.aistock.infra.gemini.dto.GeminiRequest;
import com.teamfp.aistock.infra.gemini.dto.GeminiResponse;
import com.teamfp.aistock.infra.naver.NaverNewsApiClient;
import com.teamfp.aistock.infra.naver.dto.NaverNewsSearchResponse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * feature/ai-news(맞춤형 뉴스 브리핑) — AI 재무설계사(AiPlanningService)와는 성격이 다르다.
 * 재무설계사는 사용자 질문마다 대화형으로 답하는 채팅 서비스지만, 이 기능은 사용자가 미리
 * 골라둔 언론사 설정을 바탕으로 매일 스케줄러가 알아서 오늘의 시황을 요약해 브리핑을
 * "만들어 두는" 단방향 비서다(2026-08-24 사용자 확정) — 대화 이력도, 도구 판단도 없다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiNewsService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    // 비서 톤 인사말(2026-08-24 사용자 요청) — AiPlanningService.FIRST_TURN_GREETING과 동일한
    // 이유로 프롬프트 지시만으로 맡기지 않고 코드가 항상 앞에 붙인다. Gemini에게 "인사도 네가
    // 써라"라고 맡기면 호출마다 문구가 미묘하게 달라지거나(가끔은 아예 생략하거나) 하는 문제가
    // 재무설계사 첫 인사 버그에서 이미 확인된 바 있어, 같은 실수를 반복하지 않는다 — 아래
    // SUMMARY_SYSTEM_INSTRUCTION은 반대로 "인사는 쓰지 마라"고 명시해 중복을 막는다.
    private static final String BRIEFING_GREETING = "안녕하세요, AI 시황 비서 AI STOCK입니다. 오늘의 주요 시황을 브리핑해드리겠습니다.\n\n";

    private static final String SUMMARY_SYSTEM_INSTRUCTION =
            "당신은 AI STOCK의 뉴스 브리핑 비서입니다. 인사말은 이미 별도로 붙으니 절대 쓰지 말고, "
                    + "곧바로 본론(오늘의 시황 요약)부터 시작합니다. 주어진 기사 목록만 근거로 오늘의 "
                    + "시황을 3~4문장으로 담백하게 요약합니다. 기사에 없는 내용은 추측해서 덧붙이지 "
                    + "않고, 과장된 감탄사나 광고성 말투 없이 사실 위주로 전달합니다. 코스피·코스닥 "
                    + "지수나 등락률처럼 소수점 단위까지 정확해야 하는 숫자는 옮겨적다가 틀리기 "
                    + "쉬우므로, 기사 원문에 그 숫자가 그대로 적혀 있는 경우가 아니라면 "
                    + "'6,700선', '1%대 하락'처럼 대략적인 수준으로만 표현합니다.";

    // 근거 검증(2026-08-24 1차 시도 후 보류 → 같은 날 재도입). 처음엔 검증 호출 하나만 추가했다가
    // "지어내는 현상이 가끔이라 라이브로 못 잡아냈다"는 이유로 걷어냈는데, 재테스트에서 같은
    // 데이터셋(아주경제)에 대해 "SK하이닉스 급등" 지어내기가 3회 연속 재현되어 "가끔"이 아님이
    // 확인됐다. 지수 숫자 오기재는 위 SUMMARY_SYSTEM_INSTRUCTION의 "정확한 숫자 쓰지 마라" 지시로
    // 발생 자체를 줄이고(1단계), 그걸로 못 막는 부류(있지도 않은 회사/수치를 통째로 지어내거나
    // 과거 뉴스와 오늘 뉴스를 섞는 경우)는 요약 생성 직후 별도 Gemini 호출로 "원본 기사에 실제로
    // 있는 내용인지" 한 번 더 확인해 걸러낸다(2단계) — isGrounded() 참고. JUDGE/ANSWER가 현재
    // 설정상 같은 모델(gemini-3.1-flash-lite)이라 검증 호출도 별도 모델의 "다른 시각"을 보장하진
    // 못하지만, 같은 모델이라도 "생성"과 "이미 만들어진 결과를 원문과 대조하는 것"은 서로 다른
    // 과제라 생성 단계에서 놓친 오류를 재검토 단계에서 잡아낼 여지가 있다고 판단했다.
    //
    // 완화(2026-09-21): 원래 문구("하나라도 들어가 있으면 근거 없음")가 너무 엄격해서, 원문
    // 기사 제목에 이미 있는 사실(예: 기사 제목 "삼성전자 5% 급등 '27만전자' 회복")을 살짝 다른
    // 말로 바꿔 썼을 뿐인데도("5%가량 급등하며 27만원대 회복") 연합뉴스 라이브 테스트에서 5회
    // 연속 UNSAFE로 오판했다. 완전히 새로 지어낸 사실만 걸러내도록 완화했었는데, 그 직후
    // 디지털타임스 라이브 테스트에서 부작용이 나왔다 — 원문 "7천피 회복"을 브리핑이 "2,700선
    // 회복"이라고 자릿수 자체가 다른 숫자로 썼는데도 "표현 차이"로 오인해 SAFE 처리했다(같은
    // 문구를 1차 시도에선 정확히 UNSAFE로 잡았다가 재시도에서 판정이 뒤집힌 것으로 보아, 완화된
    // 지시가 "반올림"과 "완전히 다른 수치"의 경계를 판단 AI에게 명확히 못 줬던 것이 원인으로
    // 추정된다). 그래서 "반올림/대략화(오차 10% 이내)"와 "자릿수·크기 자체가 다른 수치"를
    // 명시적으로 구분하고, 애매하면 안전한 쪽(근거 없음)으로 판단하라는 지시를 추가해 재조정했다.
    private static final String VERIFICATION_SYSTEM_INSTRUCTION =
            "당신은 AI STOCK 뉴스 브리핑의 팩트체커입니다. [원본 기사 목록]에 실제로 나온 사실을 "
                    + "근거로 [브리핑 초안]이 작성됐는지 확인합니다. 같은 수치를 반올림하거나 "
                    + "대략적으로 표현한 경우(원본과 10% 이내 오차, 예: '5% 급등'을 '5%가량 "
                    + "급등'으로, '27만전자'를 '27만원대'로 쓴 경우)는 문제 삼지 않습니다. 하지만 "
                    + "원본의 수치와 자릿수·크기 자체가 다른 경우(예: 원본 '7,000선'인데 브리핑에 "
                    + "'2,700선'처럼 명백히 다른 값), 원본 기사 어디에도 등장하지 않는 회사명·사건을 "
                    + "새로 지어낸 경우는 반드시 근거 없음으로 판단합니다. 반올림인지 완전히 다른 "
                    + "수치인지 애매하면 안전한 쪽(근거 없음)으로 판단합니다. 다른 설명 없이, 문제가 "
                    + "없으면 'SAFE' 한 단어만 답하고, 문제가 있으면 'UNSAFE: ' 뒤에 근거 없는 문장을 "
                    + "그대로 인용해 한 줄로만 답합니다.";

    // 재생성까지 포함해 최대 몇 번 요약을 만들어볼지. 완전 무제한 재시도는 검증 호출 자체가
    // 실패하는 경우(예: Gemini 응답이 'SAFE'도 'UNSAFE'도 아닌 애매한 문구) 무한 호출로 비용이
    // 새는 것을 막기 위해 상한을 둔다 — 2026-09-21 사용자 확정: "실패하면 그냥 넘어가지 말고
    // 될 때까지 다시 시도하라"는 요구로 기존 2회에서 5회로 늘렸다. 5회차까지도 근거 없음으로
    // 판정되면(마지막 시도까지 UNSAFE) generateVerifiedSummary()가 null을 반환해 이번 브리핑은
    // 폐기한다 — 지어낸 내용을 유저에게 보내느니 아예 안 보내는 게 낫다는 원칙은 그대로 유지.
    private static final int MAX_SUMMARY_ATTEMPTS = 5;

    // notifications.content가 VARCHAR(500)이라 Gemini 요약이 그보다 길면 잘라서 저장한다.
    private static final int NOTIFICATION_CONTENT_MAX_LENGTH = 500;

    // NewsRelevanceMatcher.OUTLET_NAMES(재무설계사와 공유하는 29곳 신뢰 언론사 목록)는 그대로
    // 두되, 이 기능에서 "선택 가능한 언론사"만 별도로 좁힌다. 처음엔 조선비즈·이코노미스트도
    // 여기 포함했는데, NaverNewsApiClient.searchByOutlet()의 검색어를 "증시" 하나에서 "코스피/
    // 주가/코스닥"까지 넓히자(2026-08-24) 둘 다 실제 시황 기사가 잡혀서 목록에서 뺐다(자세한
    // 경위는 NaverNewsApiClient의 GENERAL_MARKET_QUERIES 주석 참고). 디일렉(반도체 장비 전문지)
    // 만 검색어 4개·2000건 넘게 뒤져도 끝까지 시황 관련 기사가 0건이라 — 언론사 자체가 "오늘의
    // 시황"을 다루지 않는 것으로 판단해 유일하게 남겨둔다. 필터를 억지로 느슨하게 풀어 통과시키면
    // 이전에 고쳤던 "본문에 살짝 스친 무관 기사 통과" 문제가 재발하므로, 대신 처음부터 선택지에서
    // 제외해 "오늘은 기사 없음" 자체가 나오지 않도록 한다(재무설계사의 search()/신뢰 도메인
    // 필터는 이 목록과 무관하게 그대로 유지된다).
    private static final Set<String> UNRELIABLE_BRIEFING_OUTLET_DOMAINS = Set.of("thelec.kr");

    private final NewsBriefingSettingRepository newsBriefingSettingRepository;
    private final NewsBriefingRepository newsBriefingRepository;
    private final UserRepository userRepository;
    private final NaverNewsApiClient naverNewsApiClient;
    private final GeminiApiClient geminiApiClient;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    // generateBriefingForUser()의 @Transactional은 Spring AOP 프록시를 거쳐야만 실제로
    // 트랜잭션을 연다. generateDailyBriefings()가 같은 클래스 안에서 this.generateBriefingForUser(...)
    // 를 그냥 호출하면(self-invocation) 프록시를 우회해 @Transactional이 통째로 무시되고, 뉴스
    // 저장과 알림 발송이 트랜잭션 없이 따로 실행되어 버린다(예: 알림 발송이 실패해도 이미 저장된
    // 브리핑은 롤백되지 않음). AuthService.self와 동일한 패턴으로 @Lazy 프록시를 통해 호출한다.
    @Autowired
    @Lazy
    private AiNewsService self;

    /**
     * 사용자가 고를 수 있는 언론사 목록 — GET /api/ai/news/outlets.
     */
    @Transactional(readOnly = true)
    public List<NewsOutletResponse> getSelectableOutlets() {
        return NewsRelevanceMatcher.OUTLET_NAMES.entrySet().stream()
                .filter(entry -> !UNRELIABLE_BRIEFING_OUTLET_DOMAINS.contains(entry.getKey()))
                .map(entry -> NewsOutletResponse.of(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing(NewsOutletResponse::outletName))
                .toList();
    }

    /**
     * 현재 내가 설정해둔 언론사 조회. 아직 한 번도 설정한 적이 없으면 null을 반환한다
     * (설정을 안 한 것은 오류가 아니라 정상적인 초기 상태이므로 예외를 던지지 않는다).
     */
    @Transactional(readOnly = true)
    public NewsBriefingSettingResponse getMySetting(Long userId) {
        return newsBriefingSettingRepository.findByUserId(userId)
                .map(NewsBriefingSettingResponse::from)
                .orElse(null);
    }

    /**
     * 언론사·브리핑 시각 설정 저장/변경. 언론사는 한 번에 하나만 고를 수 있다(2026-08-24 확정)
     * — 이미 설정이 있으면 교체하고, 없으면 새로 만든다. 브리핑 시각(시:분:초, KST)은
     * generateDailyBriefings()가 매초 실행될 때 이 값과 현재 시각이 일치하는
     * 사용자만 골라내는 데 쓰인다.
     *
     * briefingTime은 아직 이 값을 보내지 않는 구버전 프론트와의 호환을 위해 null을 허용한다.
     * 신규 설정(null이면 NewsBriefingSetting 생성자가 DEFAULT_BRIEFING_TIME=07:00:00을 적용)과
     * 기존 설정 변경(null이면 기존 값을 그대로 둠 — 언론사만 바꾸는 요청이 시각을 07:00으로
     * 되돌려버리면 안 되므로)을 다르게 처리한다.
     */
    @Transactional
    public NewsBriefingSettingResponse updateMySetting(Long userId, String outletDomain, java.time.LocalTime briefingTime) {
        validateOutlet(outletDomain);

        NewsBriefingSetting setting = newsBriefingSettingRepository.findByUserId(userId).orElse(null);
        if (setting == null) {
            User user = userRepository.getReferenceById(userId);
            setting = NewsBriefingSetting.builder()
                    .user(user)
                    .outletDomain(outletDomain)
                    .briefingTime(briefingTime)
                    .build();
        } else {
            setting.changeOutlet(outletDomain);
            if (briefingTime != null) {
                setting.changeBriefingTime(briefingTime);
            }
        }
        NewsBriefingSetting saved = newsBriefingSettingRepository.save(setting);
        return NewsBriefingSettingResponse.from(saved);
    }

    /**
     * 오늘의 브리핑 조회. 아직 스케줄러가 만들지 않았으면(예: 가입 직후, 스케줄 실행 전) 404.
     */
    @Transactional(readOnly = true)
    public NewsBriefingResponse getTodayBriefing(Long userId) {
        NewsBriefing briefing = newsBriefingRepository.findByUserIdAndBriefingDate(userId, LocalDate.now(KST))
                .orElseThrow(() -> new CustomException(ErrorCode.NEWS_BRIEFING_NOT_FOUND));
        return NewsBriefingResponse.from(briefing, deserializeSourceLinks(briefing.getSourceLinksJson()));
    }

    @Transactional(readOnly = true)
    public List<NewsBriefingResponse> getBriefingHistory(Long userId) {
        return newsBriefingRepository.findTop100ByUserUserIdOrderByBriefingDateDesc(userId).stream()
                .map(briefing -> NewsBriefingResponse.from(briefing, deserializeSourceLinks(briefing.getSourceLinksJson()))).toList();
    }

    @Transactional(readOnly = true)
    public NewsBriefingResponse getBriefing(Long userId, LocalDate date) {
        NewsBriefing briefing = newsBriefingRepository.findByUserIdAndBriefingDate(userId, date)
                .orElseThrow(() -> new CustomException(ErrorCode.NEWS_BRIEFING_NOT_FOUND));
        return NewsBriefingResponse.from(briefing, deserializeSourceLinks(briefing.getSourceLinksJson()));
    }

    private void validateOutlet(String outletDomain) {
        if (!NewsRelevanceMatcher.OUTLET_NAMES.containsKey(outletDomain)
                || UNRELIABLE_BRIEFING_OUTLET_DOMAINS.contains(outletDomain)) {
            throw new CustomException(ErrorCode.INVALID_NEWS_OUTLET);
        }
    }

    /**
     * 매초(KST) — 언론사를 설정해둔 사용자 중 지금 시:분:초를 브리핑 시각으로 고른
     * 사용자만 골라 오늘의 브리핑을 생성한다(사용자별 시:분:초 선택 지원, v15). 예를 들어
     * 09:00:00 실행에서는 briefingTime=09:00:00인 사용자만 처리하고, 07:00:00인 사용자는
     * 그날 아침 7시에 이미 처리됐으므로 건너뛴다(뒤이어 generateBriefingForUser()의
     * existsByUserIdAndBriefingDate 체크로도 중복 생성을 한 번 더 막는다).
     *
     * 대상자 조회 방식과 그 변경 배경(PR#45 리뷰 반려 사유 4번, 2026-09-28)은
     * {@link com.teamfp.aistock.domain.ai.repository.NewsBriefingSettingRepository#findDueSettings}
     * 주석 참고 — 같은 설명을 여기서 반복하지 않는다.
     *
     * 초 단위 정밀도를 지원하려면 매시 정각이 아니라 매초 깨어나야 한다 — 실제 브리핑
     * 생성(Gemini 호출 등)은 대상자가 있을 때만 실행되므로, 대상자가 없는 대부분의 초에는
     * 이 메서드가 목록 조회 한 번만 하고 끝난다(가벼운 폴링). 다만 같은 초에 여러 명이
     * 몰리면 아래 for문이 한 명씩 순차 처리(Gemini 호출 포함)하므로 뒤 사용자는 앞 사용자
     * 처리가 끝날 때까지 기다린다 — 이번 수정은 "매초 전체조회" 비용만 없앴을 뿐 이 순차
     * 처리 자체는 그대로다(사용자 수가 늘어 문제가 되면 별도 개선 필요, 지금은 과설계로
     * 보고 손대지 않음).
     *
     * 재무설계사의 Gemini 호출과 달리 사용자가 직접 요청한 게 아니라 서버가 스스로 도는
     * 배치 작업이라, 대화용으로 설계된 RedisRateLimiterService(분당3/일일10)는 여기 적용하지
     * 않는다 — 그 한도는 사용자 한 명이 채팅을 남용하는 것을 막기 위한 것이지, 서버가 하루
     * 한 번 대신 요약해주는 이 기능과는 목적이 다르다.
     */
    @Scheduled(cron = "* * * * * *", zone = "Asia/Seoul")
    public void generateDailyBriefings() {
        java.time.LocalDateTime now = java.time.LocalDateTime.now(KST);
        LocalDate today = now.toLocalDate();
        List<NewsBriefingSetting> settings = newsBriefingSettingRepository
                .findDueSettings(now.toLocalTime().withNano(0), today);
        if (settings.isEmpty()) {
            return;
        }
        log.info("뉴스 브리핑 배치 시작 - {} 대상 사용자 {}명", now.toLocalTime(), settings.size());

        int successCount = 0;
        for (NewsBriefingSetting setting : settings) {
            try {
                if (self.generateBriefingForUser(setting, today)) {
                    successCount++;
                }
            } catch (Exception e) {
                // 한 사용자의 실패(외부 API 오류 등)가 나머지 사용자의 브리핑 생성까지
                // 막으면 안 되므로 여기서 흡수하고 다음 사용자로 넘어간다.
                log.error("뉴스 브리핑 생성 실패 - userId: {}", setting.getUser().getUserId(), e);
            }
        }
        log.info("뉴스 브리핑 배치 종료 - {}, 성공 {}건 / 대상 {}건", now.toLocalTime(), successCount, settings.size());
    }

    // 비정상 종료 시 화면에 보여줄 안내문 — 사유 2가지를 구분해서 보여준다("기사 자체가
    // 없음"과 "AI가 지어내서 못 믿음"은 서로 다른 상황이라 뭉뚱그리면 안 된다는 2026-09-21
    // 사용자 지적으로 다시 분리). 이전에는 이 경우 아무 행도 저장하지 않아 getTodayBriefing()이
    // NEWS_BRIEFING_NOT_FOUND(404 성격)를 던졌는데, 그러면 프론트가 "오류"와 "그냥 오늘은
    // 뉴스가 없음"을 구분하지 못한다 — 그래서 실패해도 화면에 보여줄 문구를 담은 행을 항상
    // 저장한다. 다만 "검증 실패" 케이스에 원문 기사 링크까지 보여주는 건 별개 문제로
    // 걷어냈다("AI가 신뢰 못 한 기사를 왜 보여주냐"는 지적) — 원문 기사는 실제 요약이
    // 완성된 날에만(= AI가 근거로 삼은 기사를 "이 요약의 출처"로 보여줄 때만) 첨부한다.
    private static final String NO_ARTICLES_TEMPLATE = BRIEFING_GREETING + "오늘은 %s에서 시황 관련 기사를 찾지 못했어요. 내일 다시 확인해 주세요.";
    private static final String VERIFICATION_FAILED_TEMPLATE = BRIEFING_GREETING + "오늘은 신뢰할 수 있는 요약을 만들지 못했어요. 내일 다시 확인해 주세요.";

    /**
     * @return 실제로 AI 요약을 만들어 브리핑을 완성했으면 true. 이미 오늘 처리된 경우(건너뜀),
     * 기사를 못 찾은 경우, 근거 검증을 끝내 통과하지 못한 경우는 전부 false를 반환하지만—
     * 뒤의 두 실패 케이스는 그냥 건너뛰지 않고 사유별로 다른 안내 문구를 담은 행을 저장한다
     * (사용자가 "왜 아무것도 안 보이는지" 알 수 있어야 한다는 2026-09-21 요청 반영). 원문 기사
     * 링크(sourceLinks)는 실제 요약이 완성된 경우(true)에만 채워지고, 실패 시엔 항상 비어있다.
     */
    @Transactional
    public boolean generateBriefingForUser(NewsBriefingSetting setting, LocalDate today) {
        Long userId = setting.getUser().getUserId();
        if (newsBriefingRepository.existsByUserIdAndBriefingDate(userId, today)) {
            return false;
        }

        String outletName = NewsRelevanceMatcher.OUTLET_NAMES.getOrDefault(setting.getOutletDomain(), "확인된 매체");
        NaverNewsSearchResponse newsResponse = naverNewsApiClient.searchByOutlet(setting.getOutletDomain());

        String content;
        List<NewsSourceLinkDto> sourceLinks;
        boolean generated;
        if (newsResponse.results().isEmpty()) {
            log.info("뉴스 브리핑 생성 스킵 - 조건에 맞는 기사 없음 (userId={}, outlet={})", userId, setting.getOutletDomain());
            content = String.format(NO_ARTICLES_TEMPLATE, outletName);
            sourceLinks = List.of();
            generated = false;
        } else {
            String summary = generateVerifiedSummary(newsResponse, outletName);
            if (summary == null) {
                log.warn("뉴스 브리핑 생성 스킵 - 최대 재시도까지 근거 검증 실패 (userId={}, outlet={})", userId, setting.getOutletDomain());
                content = VERIFICATION_FAILED_TEMPLATE;
                sourceLinks = List.of();
                generated = false;
            } else {
                sourceLinks = newsResponse.results().stream()
                        .map(result -> new NewsSourceLinkDto(result.title(), result.link(), result.outlet()))
                        .toList();
                content = summary;
                generated = true;
            }
        }

        NewsBriefing briefing = NewsBriefing.builder()
                .user(setting.getUser())
                .outletDomain(setting.getOutletDomain())
                .briefingDate(today)
                .content(content)
                .sourceLinksJson(serializeSourceLinks(sourceLinks))
                .build();
        newsBriefingRepository.save(briefing);

        if (generated) {
            notificationService.notify(userId, NotificationType.NEWS,
                    outletName + " 오늘의 브리핑이 도착했어요", truncateForNotification(content));
        }
        return generated;
    }

    /**
     * 요약 생성 → 근거 검증까지 마친 최종 브리핑 본문(인사말 포함)을 만든다. 검증에서 근거
     * 없음(UNSAFE)이 나오면 MAX_SUMMARY_ATTEMPTS 안에서 폐기 후 재생성을 시도하고, 그래도
     * 마지막 시도까지 근거 없음이면 지어낸 내용을 사용자에게 보내느니 이번 브리핑 자체를
     * 포기한다(null 반환) — 증권 정보는 틀린 걸 보내는 것보다 아예 안 보내는 게 낫다는 판단
     * (2026-08-24 확정). 호출부(generateBriefingForUser)는 null이면 저장·알림 없이 스킵한다.
     */
    private String generateVerifiedSummary(NaverNewsSearchResponse newsResponse, String outletName) {
        String articles = buildArticlesText(newsResponse);

        String summary = null;
        boolean grounded = false;
        for (int attempt = 1; attempt <= MAX_SUMMARY_ATTEMPTS && !grounded; attempt++) {
            if (attempt > 1) {
                log.warn("뉴스 브리핑 근거 검증 실패 - 폐기 후 재생성 {}회차 시도 (outlet={})", attempt, outletName);
            }
            summary = requestSummary(articles, outletName);
            grounded = isGrounded(articles, summary);
        }
        if (!grounded) {
            log.warn("뉴스 브리핑 근거 검증 - 최대 재시도({}회)까지 근거 없음 판정, 이번 브리핑은 폐기 (outlet={})",
                    MAX_SUMMARY_ATTEMPTS, outletName);
            return null;
        }
        return BRIEFING_GREETING + summary;
    }

    private String buildArticlesText(NaverNewsSearchResponse newsResponse) {
        StringBuilder articles = new StringBuilder();
        for (NaverNewsSearchResponse.NaverNewsResult result : newsResponse.results()) {
            articles.append("- ").append(result.title());
            if (result.description() != null && !result.description().isBlank()) {
                articles.append(" : ").append(result.description());
            }
            articles.append('\n');
        }
        return articles.toString();
    }

    private String requestSummary(String articles, String outletName) {
        String prompt = outletName + "의 오늘자 주요 시황 기사입니다. 이 기사들을 종합해서 오늘의 시황을 요약해 주세요:\n" + articles;

        GeminiRequest request = new GeminiRequest(
                SUMMARY_SYSTEM_INSTRUCTION,
                prompt,
                List.of(),
                List.of(),
                List.of(),
                GeminiRequest.GeminiModel.ANSWER);
        GeminiResponse response = geminiApiClient.generate(request);
        return response.content();
    }

    /**
     * summary가 articles(원본 기사 목록)만으로 뒷받침되는지 별도 Gemini 호출로 대조한다.
     * 응답이 "SAFE"로 시작하지 않으면(명시적 UNSAFE는 물론, 형식을 벗어난 애매한 응답까지)
     * 안전하지 않은 것으로 보수적으로 처리해 재생성을 유도한다.
     */
    private boolean isGrounded(String articles, String summary) {
        String prompt = "[원본 기사 목록]\n" + articles + "\n[브리핑 초안]\n" + summary;

        GeminiRequest request = new GeminiRequest(
                VERIFICATION_SYSTEM_INSTRUCTION,
                prompt,
                List.of(),
                List.of(),
                List.of(),
                GeminiRequest.GeminiModel.JUDGE);
        GeminiResponse response = geminiApiClient.generate(request);
        String verdict = response.content();
        boolean grounded = verdict != null && verdict.trim().startsWith("SAFE");
        if (!grounded) {
            log.warn("뉴스 브리핑 근거 검증 판정 - {}", verdict);
        }
        return grounded;
    }

    // MySQL JSON 컬럼(source_links) 직렬화/역직렬화 — SimulationService.parseScenarioData()와
    // 동일한 패턴(Jackson 3 체크 예외를 CustomException으로 감싸 던짐). Redis 직렬화 오류가
    // 아니라 DB 컬럼 파싱이므로 REDIS_SERIALIZATION_ERROR를 재사용하지 않고 별도 코드를 쓴다.
    private String serializeSourceLinks(List<NewsSourceLinkDto> sourceLinks) {
        try {
            return objectMapper.writeValueAsString(sourceLinks);
        } catch (JacksonException e) {
            throw new CustomException(ErrorCode.NEWS_SOURCE_DATA_PARSE_ERROR, e);
        }
    }

    private List<NewsSourceLinkDto> deserializeSourceLinks(String sourceLinksJson) {
        try {
            NewsSourceLinkDto[] sourceLinks = objectMapper.readValue(sourceLinksJson, NewsSourceLinkDto[].class);
            return sourceLinks == null ? List.of() : List.of(sourceLinks);
        } catch (JacksonException e) {
            throw new CustomException(ErrorCode.NEWS_SOURCE_DATA_PARSE_ERROR, e);
        }
    }

    private String truncateForNotification(String content) {
        if (content.length() <= NOTIFICATION_CONTENT_MAX_LENGTH) {
            return content;
        }
        return content.substring(0, NOTIFICATION_CONTENT_MAX_LENGTH);
    }
}
