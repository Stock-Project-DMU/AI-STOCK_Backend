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

    // SECURITIES_NEWS_DOMAINS 29곳의 도메인 → 사람이 읽는 한글 언론사명 매핑(2026-08-06
    // NaverNewsApiClient에 최초 추가, 2026-08-24 feature/ai-news에서 이 클래스로 이동) — 사용자가
    // "이거 어디 기사야?" 하고 물었을 때 Gemini가 실제 매체명을 인용할 수 있게 하려는 목적으로
    // 만들어졌는데, feature/ai-news(맞춤형 뉴스 브리핑)에서 "사용자가 언론사를 이름으로 골라서
    // 도메인으로 변환"하는 반대 방향 조회에도 그대로 재사용할 수 있어 공용 위치로 옮겼다(다른
    // 클라이언트가 언론사 이름/도메인을 다시 손으로 베끼는 것을 막기 위함 — 이 파일 상단 설계
    // 배경과 동일한 이유).
    public static final Map<String, String> OUTLET_NAMES = Map.ofEntries(
            Map.entry("hankyung.com", "한국경제"),
            Map.entry("mk.co.kr", "매일경제"),
            Map.entry("edaily.co.kr", "이데일리"),
            Map.entry("fnnews.com", "파이낸셜뉴스"),
            Map.entry("einfomax.co.kr", "연합인포맥스"),
            Map.entry("sedaily.com", "서울경제"),
            Map.entry("mt.co.kr", "머니투데이"),
            Map.entry("biz.chosun.com", "조선비즈"),
            Map.entry("heraldcorp.com", "헤럴드경제"),
            Map.entry("asiae.co.kr", "아시아경제"),
            Map.entry("newspim.com", "뉴스핌"),
            Map.entry("yna.co.kr", "연합뉴스"),
            Map.entry("newsis.com", "뉴시스"),
            Map.entry("news1.kr", "뉴스1"),
            Map.entry("wowtv.co.kr", "한국경제TV"),
            Map.entry("biz.sbs.co.kr", "SBS Biz"),
            Map.entry("imnews.imbc.com", "MBC뉴스"),
            Map.entry("news.kbs.co.kr", "KBS뉴스"),
            Map.entry("news.sbs.co.kr", "SBS뉴스"),
            Map.entry("thelec.kr", "디일렉"),
            Map.entry("etnews.com", "전자신문"),
            Map.entry("dt.co.kr", "디지털타임스"),
            Map.entry("ajunews.com", "아주경제"),
            Map.entry("etoday.co.kr", "이투데이"),
            Map.entry("businesspost.co.kr", "비즈니스포스트"),
            Map.entry("economist.co.kr", "이코노미스트"),
            Map.entry("bizwatch.co.kr", "비즈워치"),
            Map.entry("biz.newdaily.co.kr", "뉴데일리경제"),
            Map.entry("techm.kr", "테크M"));

    // host가 domain 자체이거나 domain의 하위 도메인일 때만 true — 단순 endsWith만 쓰면
    // "fakesedaily.com"이 "sedaily.com"(서울경제)으로 오매칭되는 등, 접미사만 같은 사칭
    // 도메인까지 신뢰 매체로 잘못 인식하게 된다("." 경계가 있어야 진짜 하위 도메인이다).
    // NaverNewsApiClient.matchesDomain()에서 2026-08-06 최초 작성, 2026-08-24 이 클래스로 이동.
    public static boolean matchesDomain(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }

    // feature/ai-news 전용 — 회사명이 없는 "종합 시황" 조회(NaverNewsApiClient.searchByOutlet())는
    // 검색어("증시") 하나만으로는 진짜 시황 관련 기사인지 보장하지 못한다. 실제 라이브 테스트로
    // 확인(2026-08-24, 29개 언론사 전수 조사) — 신뢰 매체인 연합뉴스에서 "증시"로 검색했는데
    // 본문 한 줄에만 "그 회사가 최근 상장했다"는 문구가 스친 도핑 스캔들 기사가 섞여 들어왔다.
    // 처음엔 제목+본문 요약 둘 다 확인했는데, 그러면 이런 "본문에 살짝 스친" 기사까지 통과해버려
    // — "시황 브리핑이면 제목부터 증시 얘기여야 한다"는 판단에 따라 제목만 확인하도록 좁혔다
    // (2026-08-24 사용자 확정, search()의 회사명 매칭이 제목만 보는 것과 동일한 원칙).
    private static final List<String> MARKET_KEYWORDS = List.of(
            "증시", "코스피", "코스닥", "주가", "주식", "증권", "상장", "환율",
            "매도", "매수", "순매수", "순매도", "급등", "급락", "폭등", "폭락",
            "지수", "종목", "ETF", "IPO", "유상증자", "시가총액", "시총",
            "특징주", "대형주", "우량주", "테마주", "주주환원", "리레이팅");

    public static boolean isMarketRelevant(String title) {
        if (title == null) {
            return false;
        }
        return MARKET_KEYWORDS.stream().anyMatch(title::contains);
    }

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
