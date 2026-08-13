package com.teamfp.aistock.global.util;

import java.util.List;
import java.util.Map;

/**
 * NaverNewsApiClient/TavilyApiClient가 공통으로 쓰는 뉴스 관련성 판단 로직 — 신뢰 매체 도메인
 * 목록, 계열사 접미사를 뗀 회사명 제목 매칭, 기간(periodDays) 유효 범위 검증을 한 곳에 모은다.
 * 두 클라이언트가 이 로직을 각자 복붙해 쓰다가(2026-08-06 도메인 7곳 추가를 두 파일에 수동으로
 * 반영해야 했던 것처럼) 한쪽만 고치고 다른 쪽을 놓치는 문제가 실제로 있었다. TavilyApiClient는
 * 현재 미사용(추후 AI 시황 브리핑용으로 보류)이지만, 재활성화될 때도 이 로직이 자동으로 최신
 * 상태를 따라가게 하기 위해 여기로 모았다.
 */
public final class NewsRelevanceMatcher {

    private NewsRelevanceMatcher() {
    }

    // AI 재무설계 상담이 참고할 뉴스를 증권/경제 관련 정식 언론사로만 한정한다(2026-07-31 최초
    // 5곳 확정, 2026-08-05 22곳으로 확장, 2026-08-06 실제 검색 결과 도메인 감사로 7곳 추가해
    // 29곳). 경제/증권 전문지, 통신사, 증권·경제 전문 방송, 공중파 종합 방송, 산업 전문지 5개
    // 카테고리로 나눠 검토했고, 전부 정식 언론사라 품질(신뢰도) 리스크는 없다고 판단해 카테고리당
    // 최소 1곳이 아니라 후보 전부를 포함했다.
    public static final List<String> SECURITIES_NEWS_DOMAINS = List.of(
            "hankyung.com",
            "mk.co.kr",
            "edaily.co.kr",
            "fnnews.com",
            "einfomax.co.kr",
            "sedaily.com",
            "mt.co.kr",
            "biz.chosun.com",
            "heraldcorp.com",
            "asiae.co.kr",
            "newspim.com",
            "yna.co.kr",
            "newsis.com",
            "news1.kr",
            "wowtv.co.kr",
            "biz.sbs.co.kr",
            "imnews.imbc.com",
            "news.kbs.co.kr",
            "news.sbs.co.kr",
            "thelec.kr",
            "etnews.com",
            "dt.co.kr",
            // 2026-08-06 실제 검색 결과의 도메인 분포를 직접 감사(audit)해서 추가 - 증권/경제
            // 전문 매체인데도 기존 22곳 목록에 없어 매번 걸러지고 있던 것을 실측으로 확인했다.
            "ajunews.com",
            "etoday.co.kr",
            "businesspost.co.kr",
            "economist.co.kr",
            "bizwatch.co.kr",
            "biz.newdaily.co.kr",
            "techm.kr"
    );

    // "삼성전자"가 기사 제목엔 "삼성"으로 축약돼 나오는 경우가 많아, 흔한 계열사 접미사를 뗀
    // 축약명도 함께 확인한다.
    private static final List<String> COMPANY_SUFFIXES = List.of(
            "전자", "그룹", "홀딩스", "증권", "화학", "물산", "카드", "생명", "에너지", "중공업", "건설", "전기", "바이오로직스");

    // 접미사를 뗀 축약명 길이가 최소 3자는 돼야 매칭을 허용한다(2026-08-06 실제 라이브 테스트로
    // 발견) - "삼성전자"→"삼성", "삼성물산"→"삼성", "삼성생명"→"삼성"처럼 국내 그룹명은 대부분
    // 2글자라, 2자 축약을 그대로 허용하면 "삼성생명"을 찾았는데 "삼성"이 들어간 삼성전기·삼성증권
    // 기사까지 다 걸려버리는 계열사 뒤섞임이 실측으로 확인됐다(5건 중 4건이 삼성생명과 무관).
    // 그룹명 단독으로는 어차피 특정 계열사를 안전하게 가리킬 수 없다는 원칙(AiPlanningService의
    // "그룹명은 되묻는다" 판단과 같은 맥락)을 매칭 로직에도 반영한 것이다.
    private static final int MIN_STRIPPED_STEM_LENGTH = 3;

    // 접미사를 뗀 축약명(예: "삼성")은 MIN_STRIPPED_STEM_LENGTH 미만이라 위 메커니즘으로는
    // 구제되지 않는 업계 표준 약칭을 여기 명시적으로 등록한다(2026-08-11 추가 — 라이브
    // 테스트에서 실측: "코스피, 호르무즈 협상 신뢰 약화... 반도체 투톱도 약세", "삼전닉스
    // 모멘텀 실종?" 등 신뢰 매체의 진짜 관련 기사가 제목에 "삼성전자"가 그대로 없어서 전부
    // 걸러지고 있었다). "반도체 투톱"처럼 여러 회사를 가리키는 일반 서술어는 특정 회사명을
    // 등록해도 못 잡으므로 다루지 않는다 — 회사 고유의 확립된 약칭만 등록한다.
    private static final Map<String, List<String>> KNOWN_ALIASES = Map.of(
            "삼성전자", List.of("삼전"));

    // 실제 라이브 응답으로 확인된 문제(2026-08-05): 검색어는 "삼성전자"인데 기사 제목은 "삼성
    // 첫 스마트안경"처럼 계열사 접미사를 뗀 축약형으로 나오는 경우가 많아, 토큰을 통째로
    // 요구하면 실제로 관련 있는 기사까지 걸러졌다. 흔한 대기업 계열사 접미사를 뗀 축약명도
    // 함께 확인해 이런 경우를 구제한다.
    public static boolean titleMatchesToken(String title, String token) {
        if (title.contains(token)) {
            return true;
        }
        for (String suffix : COMPANY_SUFFIXES) {
            if (token.endsWith(suffix) && token.length() - suffix.length() >= MIN_STRIPPED_STEM_LENGTH
                    && title.contains(token.substring(0, token.length() - suffix.length()))) {
                return true;
            }
        }
        for (String alias : KNOWN_ALIASES.getOrDefault(token, List.of())) {
            if (title.contains(alias)) {
                return true;
            }
        }
        return false;
    }

    // topic은 프롬프트가 "짧은 키워드로 뽑아라"라고 지시해도, 모델이 가끔 "중동 정세 및 이란
    // 리스크"처럼 여러 단어로 된 문장을 그대로 채우는 사례가 실측됨(2026-08-11) — 이 문장
    // 전체가 토씨 하나 안 틀리고 기사에 그대로 실릴 리 없어 titleMatchesToken()만으로는 100%
    // 놓친다. 프롬프트 준수에만 기대지 않고, topic을 공백 기준으로 쪼개 그 중 하나라도 실려
    // 있으면 관련 있다고 인정한다 — "및"·"관련"처럼 의미 없이 흔한 연결어만 걸러내면 나머지
    // 단어(중동/이란/리스크 등)는 그 자체로 충분히 구체적이라 오탐 위험이 낮다.
    private static final List<String> TOPIC_STOPWORDS = List.of("및", "관련", "그리고", "때문에", "이슈");
    private static final int MIN_TOPIC_TOKEN_LENGTH = 2;

    public static boolean topicMatchesAnyToken(String text, String topic) {
        if (text == null || topic == null) {
            return false;
        }
        if (titleMatchesToken(text, topic)) {
            return true;
        }
        for (String token : topic.split("[\\s,·/]+")) {
            if (token.length() >= MIN_TOPIC_TOKEN_LENGTH && !TOPIC_STOPWORDS.contains(token) && text.contains(token)) {
                return true;
            }
        }
        return false;
    }

    // "최근 뉴스"라는 말이 뜻하는 기본 범위(3단계 조건 미적용 시)를 한 달로 잡는다 — 그 이상
    // 지난 뉴스는 투자 판단 근거로 최신성이 떨어진다. periodDays를 명시하지 않은 일반적인
    // "요즘 어때?" 류 질문에는 이 기본값이 적용되므로 90일→2년으로 올린 뒤에도 그대로 30 유지.
    private static final int DEFAULT_PERIOD_DAYS = 30;
    // Gemini가 스키마와 다르게 0/음수를 보내거나(파싱 오류·환각) 비현실적으로 큰 값을 보내는
    // 경우까지 방어한다. 최소 1일(그보다 작으면 검색 범위가 아예 없어짐).
    // 2026-08-11 수정 — 원래 최대 90일이었는데, 라이브 테스트에서 사용자가 "몇 년 전" 사건을
    // 물었을 때 네이버 자체는 관련 기사를 이미 찾아줬을 수 있는데도 90일 컷오프 때문에 우리
    // 코드가 스스로 버리고 있었다는 게 드러남(네이버 검색 API 자체에는 기간 조건이 없고, 우리가
    // 응답을 받은 뒤 클라이언트에서 사후 필터링하는 구조 — NaverNewsApiClient.search() 참고).
    // 사용자와 상의해 "너무 오래된 것까지 다 가져오면 오히려 혼란스럽다"는 우려와 "그래도 못
    // 찾는 건 아쉽다"는 요구를 절충해 최대 2년(730일)으로 확장 — 그 이상은 여전히 범위 밖이며,
    // AI가 그 경계를 명시적으로 안내해야 한다(NEWS_SEARCH_TOOL의 periodDays 설명 참고).
    private static final int MIN_PERIOD_DAYS = 1;
    private static final int MAX_PERIOD_DAYS = 730;

    public static int resolvePeriodDays(Integer periodDays) {
        if (periodDays == null) {
            return DEFAULT_PERIOD_DAYS;
        }
        return Math.max(MIN_PERIOD_DAYS, Math.min(MAX_PERIOD_DAYS, periodDays));
    }
}
