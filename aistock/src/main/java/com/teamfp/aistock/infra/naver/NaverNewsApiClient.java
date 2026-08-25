package com.teamfp.aistock.infra.naver;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.HtmlUtils;

import com.teamfp.aistock.global.util.ExternalApiInvoker;
import com.teamfp.aistock.global.util.NewsRelevanceMatcher;
import com.teamfp.aistock.infra.naver.dto.NaverNewsSearchRequest;
import com.teamfp.aistock.infra.naver.dto.NaverNewsSearchResponse;
import com.teamfp.aistock.infra.naver.dto.NaverNewsSearchResponse.NaverNewsResult;

import lombok.extern.slf4j.Slf4j;

/**
 * 네이버 뉴스 검색(NAVER API HUB, NCP 콘솔 발급) 호출 담당. AI 재무설계 상담(feature/ai-planning)의
 * 뉴스 검색 백엔드를 Tavily에서 이걸로 교체했다(2026-08-05) — Tavily의 AI 자동요약(answer)이
 * 완전히 동일한 검색어로 다시 호출해도 결과가 재현되지 않는 문제(같은 "신한지주 관련 뉴스" 질문이
 * 10분 사이에 "실제 데이터 있음" → "관련 기사 0건"으로 뒤바뀜)를 라이브 테스트로 확인한 뒤 내린
 * 결정이다.
 *
 * <p>인증 방식 주의 — NCP 콘솔(console.ncloud.com/naver-api-hub)에서 발급받은 인증정보는 옛
 * developers.naver.com 방식(X-Naver-Client-Id/X-Naver-Client-Secret, openapi.naver.com)이
 * 아니라 NCP API Gateway 방식(X-NCP-APIGW-API-KEY-ID/X-NCP-APIGW-API-KEY,
 * naverapihub.apigw.ntruss.com)을 써야 한다 — 실제로 옛 방식으로 호출해 401을 반복 확인한 뒤
 * NCP 공식 문서(guide.ncloud-docs.com)로 맞는 방식을 확인했다(2026-08-05).</p>
 *
 * <p>Tavily와의 구조적 차이 — 네이버는 (1) Tavily의 include_domains 같은 매체 지정 파라미터가
 * 없어 응답을 받은 뒤 클라이언트에서 직접 매체 도메인으로 걸러야 하고, (2) Tavily의 "days" 같은
 * 기간 필터 파라미터도 없어 각 기사의 pubDate로 클라이언트에서 직접 걸러야 하고, (3) Tavily의
 * answer(자동 종합요약) 같은 게 없어 검색된 기사 목록 자체를 재료로 쓴다(NaverNewsSearchResponse
 * 참고) — (3)은 오히려 Tavily의 "요약을 못 믿겠다"는 문제를 구조적으로 없애준다.</p>
 */
@Slf4j
@Component
public class NaverNewsApiClient {

    // 매체 지정 파라미터가 없어 넉넉히 받아온 뒤 신뢰 매체로 걸러야 하므로, 네이버가 허용하는
    // 최댓값(100)으로 요청한다.
    private static final int RAW_FETCH_COUNT = 100;
    private static final int MAX_RESULTS = 5;
    // 정확도순(sim)이 최신순(date)보다 회사 관련성이 훨씬 높다 — 라이브 테스트로 확인(2026-08-05):
    // date는 검색어와 무관한 그날 최신 기사가 섞였지만, sim은 5개 전부 실제 회사 관련 기사였다.
    // topic이 있을 때는 예외적으로 최신순을 쓴다 — search() 안의 2026-08-11 주석 참고.
    private static final String SORT_SIMILARITY = "sim";
    private static final String SORT_DATE = "date";
    private static final String FORMAT_JSON = "json";
    // 신뢰 매체 도메인 목록·회사명 접미사 매칭·기간 유효 범위는 TavilyApiClient와 공유하는
    // 로직이라 NewsRelevanceMatcher(global/util)로 옮겼다 — 두 클라이언트가 각자 복붙해 쓰다가
    // 한쪽만 고치는 사고(2026-08-06 도메인 7곳 추가 때 실제로 두 파일 다 손으로 반영해야 했음)를
    // 막기 위함이다. 네이버는 include_domains 파라미터가 없어 응답을 받은 뒤 각 기사의
    // originallink 도메인이 NewsRelevanceMatcher.SECURITIES_NEWS_DOMAINS에 있는지 직접 확인한다.

    // NewsRelevanceMatcher.SECURITIES_NEWS_DOMAINS 29곳의 도메인 → 사람이 읽는 한글 언론사명 매핑(2026-08-06 추가,
    // 축9 "출처/신뢰성 확인 요청" 대응). 사용자가 "이거 어디 기사야?" 하고 물었을 때 Gemini가
    // 최종 답변에서 실제 매체명을 인용할 수 있게 하려는 것 — 그 전까지는 title/description/
    // link/pubDate만 넘겨서 링크 URL 말고는 어느 언론사인지 답변에서 밝힐 근거가 없었다.
    private static final java.util.Map<String, String> OUTLET_NAMES = java.util.Map.ofEntries(
            java.util.Map.entry("hankyung.com", "한국경제"),
            java.util.Map.entry("mk.co.kr", "매일경제"),
            java.util.Map.entry("edaily.co.kr", "이데일리"),
            java.util.Map.entry("fnnews.com", "파이낸셜뉴스"),
            java.util.Map.entry("einfomax.co.kr", "연합인포맥스"),
            java.util.Map.entry("sedaily.com", "서울경제"),
            java.util.Map.entry("mt.co.kr", "머니투데이"),
            java.util.Map.entry("biz.chosun.com", "조선비즈"),
            java.util.Map.entry("heraldcorp.com", "헤럴드경제"),
            java.util.Map.entry("asiae.co.kr", "아시아경제"),
            java.util.Map.entry("newspim.com", "뉴스핌"),
            java.util.Map.entry("yna.co.kr", "연합뉴스"),
            java.util.Map.entry("newsis.com", "뉴시스"),
            java.util.Map.entry("news1.kr", "뉴스1"),
            java.util.Map.entry("wowtv.co.kr", "한국경제TV"),
            java.util.Map.entry("biz.sbs.co.kr", "SBS Biz"),
            java.util.Map.entry("imnews.imbc.com", "MBC뉴스"),
            java.util.Map.entry("news.kbs.co.kr", "KBS뉴스"),
            java.util.Map.entry("news.sbs.co.kr", "SBS뉴스"),
            java.util.Map.entry("thelec.kr", "디일렉"),
            java.util.Map.entry("etnews.com", "전자신문"),
            java.util.Map.entry("dt.co.kr", "디지털타임스"),
            java.util.Map.entry("ajunews.com", "아주경제"),
            java.util.Map.entry("etoday.co.kr", "이투데이"),
            java.util.Map.entry("businesspost.co.kr", "비즈니스포스트"),
            java.util.Map.entry("economist.co.kr", "이코노미스트"),
            java.util.Map.entry("bizwatch.co.kr", "비즈워치"),
            java.util.Map.entry("biz.newdaily.co.kr", "뉴데일리경제"),
            java.util.Map.entry("techm.kr", "테크M"));

    private final RestClient restClient;

    @Value("${app.naver.client-id}")
    private String clientId;

    @Value("${app.naver.client-secret}")
    private String clientSecret;

    @Value("${app.naver.api-url}")
    private String apiUrl;

    public NaverNewsApiClient(RestClient.Builder restClientBuilder) {
        // 실제 라이브 호출로 확인된 사실(2026-08-06) - 네이버 뉴스 검색 API는 format=json으로
        // 요청해도 응답 Content-Type을 application/json이 아니라 text/plain;charset=UTF-8로
        // 내려준다. RestClient의 기본 MappingJackson2HttpMessageConverter는 application/json류만
        // 지원해서 이 응답을 못 읽고 UnknownContentTypeException으로 매번 실패했다(유닛 테스트는
        // MockRestServiceServer가 항상 application/json을 명시해서 이 문제를 못 잡아냈다). 이
        // 클라이언트 전용 RestClient에만 text/plain도 JSON으로 읽도록 컨버터를 추가한다 - 공유
        // restClientBuilder를 clone()해서 다른 인프라 클라이언트(Gemini/DART)에는 영향이
        // 없게 한다.
        MappingJackson2HttpMessageConverter jsonEvenAsTextPlain = new MappingJackson2HttpMessageConverter();
        jsonEvenAsTextPlain.setSupportedMediaTypes(List.of(MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN));
        this.restClient = restClientBuilder.clone()
                .messageConverters(converters -> converters.add(0, jsonEvenAsTextPlain))
                .build();
    }

    // 2026-08-11 수정 — 원래는 topic이 있으면 "회사명 topic"을 한 문장으로 합쳐서 네이버에
    // 보냈는데, 라이브 테스트에서 실측: "삼성전자 이란"으로 검색하면 네이버 자체가 돌려주는
    // 후보 100건 중 "삼성전자"가 제목에 들어간 기사가 0건이었다. 반면 "삼성전자"만 단독으로
    // 검색하면 같은 시점에 중동/이란 관련 내용을 담은 진짜 관련 기사가 실제로 후보에 들어있었다
    // (본문 요약(description)에 "호르무즈 해협", "중동발 지정학적 불확실성" 등으로 언급됨,
    // 제목엔 없었음). 즉 회사명+주제어를 합쳐서 검색하면 네이버의 유사도 랭킹이 오히려 진짜
    // 관련 기사를 후보에서 밀어내는 역효과가 있었다. 그래서 항상 회사명만으로 넓게 검색하고,
    // 주제어는 이미 하던 대로 결과를 받은 뒤 isRelevant()에서 제목·본문 요약 중 하나에 있는지로
    // 걸러낸다(회사명+주제어 결합은 검색어가 아니라 사후 필터링 단계에서만 쓴다).
    public NaverNewsSearchResponse search(NaverNewsSearchRequest request) {
        boolean hasTopic = request.topic() != null && !request.topic().isBlank();
        String queryText = request.companyName();
        int periodDays = NewsRelevanceMatcher.resolvePeriodDays(request.periodDays());
        ZonedDateTime cutoff = ZonedDateTime.now().minusDays(periodDays);
        // topic이 있을 때만 최신순(date)으로 바꾼다(2026-08-11 추가 실측) — "삼성전자" 단독
        // 정확도순(sim) 상위 100건 중 실제 중동/이란 이슈를 언급한 건 2건뿐이었고 나머지는
        // 폴더블폰·게임스컴 같은 시점 무관 홍보성 기사였다. 반면 최신순 100건에는 그날 실제로
        // 벌어진 관련 이슈가 자연스럽게 포함됐다. topic이 없을 때(예: "삼성전자 소식 알려줘")는
        // 아래 SORT_SIMILARITY 주석에 남아있는 2026-08-05 실측(정확도순 5개 전부 회사 관련,
        // 최신순은 무관한 기사가 섞임)이 여전히 유효해 그대로 정확도순을 쓴다 — topic 필터가
        // 없는 상황에서는 "최근성"보다 "회사와의 관련성"이 결과 품질에 더 중요하기 때문이다.
        String sort = hasTopic ? SORT_DATE : SORT_SIMILARITY;

        NaverApiResponse response = ExternalApiInvoker.call(() -> restClient.get()
                        .uri(uriBuilder -> uriBuilder
                                .scheme("https")
                                .host(hostOf(apiUrl))
                                .path(pathOf(apiUrl))
                                .queryParam("query", queryText)
                                .queryParam("display", RAW_FETCH_COUNT)
                                .queryParam("sort", sort)
                                .queryParam("format", FORMAT_JSON)
                                .build())
                        .header("X-NCP-APIGW-API-KEY-ID", clientId)
                        .header("X-NCP-APIGW-API-KEY", clientSecret)
                        .retrieve()
                        .body(NaverApiResponse.class),
                "네이버 뉴스 검색 API 호출 실패 - companyName: {}, topic: {}", request.companyName(), request.topic());

        return toSearchResponse(response, request.companyName(), hasTopic ? request.topic() : null, cutoff);
    }

    private String hostOf(String url) {
        java.net.URI uri = java.net.URI.create(url);
        return uri.getHost();
    }

    private String pathOf(String url) {
        java.net.URI uri = java.net.URI.create(url);
        return uri.getPath();
    }

    // 1단계(회사명, 제목에서만 확인) → 2단계(주제, topic이 있으면 제목·본문 어디든) → 3단계(기간,
    // pubDate가 cutoff 이후인지) → 매체(originallink 도메인이 신뢰 목록에 있는지) 순으로 거른다.
    // TavilyApiClient의 관련성 판단과 동일한 원칙 — 회사명은 제목 신호만 신뢰하고(본문은 사이트
    // 메뉴 등 잡음이 섞일 수 있음), 주제어는 제목·본문 둘 중 하나에만 있어도 인정한다(헤드라인은
    // 함축적으로 쓰고 본문에서 풀어 설명하는 기사가 많다, 2026-08-05 확인).
    private NaverNewsSearchResponse toSearchResponse(NaverApiResponse response, String companyName, String topic, ZonedDateTime cutoff) {
        if (response == null || response.items() == null) {
            return new NaverNewsSearchResponse(List.of());
        }

        List<NaverNewsResult> filtered = response.items().stream()
                .filter(item -> isTrustedDomain(item))
                .filter(item -> isWithinPeriod(item, cutoff))
                .map(this::stripHtmlFields)
                .filter(item -> isRelevant(item, companyName, topic))
                .map(item -> new NaverNewsResult(item.title(), item.description(), item.link(), item.pubDate(), resolveOutletName(item)))
                .limit(MAX_RESULTS)
                .toList();

        return new NaverNewsSearchResponse(filtered);
    }

    // host가 domain 자체이거나 domain의 하위 도메인일 때만 true — 단순 endsWith만 쓰면
    // "fakesedaily.com"이 "sedaily.com"(서울경제)으로 오매칭되는 등, 접미사만 같은 사칭
    // 도메인까지 신뢰 매체로 잘못 인식하게 된다("." 경계가 있어야 진짜 하위 도메인이다).
    private boolean matchesDomain(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }

    private boolean isTrustedDomain(NaverApiItem item) {
        String source = item.originallink() != null && !item.originallink().isBlank() ? item.originallink() : item.link();
        if (source == null) {
            return false;
        }
        try {
            String host = java.net.URI.create(source).getHost();
            if (host == null) {
                return false;
            }
            return NewsRelevanceMatcher.SECURITIES_NEWS_DOMAINS.stream().anyMatch(domain -> matchesDomain(host, domain));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    // isTrustedDomain()을 이미 통과한 기사만 여기 온다는 전제라 OUTLET_NAMES에 매칭되는 도메인이
    // 항상 있어야 정상이지만, 혹시 매칭에 실패해도(방어적으로) null 대신 "확인된 매체"로 대체해
    // 최종 답변에서 출처 안내 문장이 어색해지지 않게 한다.
    private String resolveOutletName(NaverApiItem item) {
        String source = item.originallink() != null && !item.originallink().isBlank() ? item.originallink() : item.link();
        try {
            String host = java.net.URI.create(source).getHost();
            return OUTLET_NAMES.entrySet().stream()
                    .filter(entry -> matchesDomain(host, entry.getKey()))
                    .map(java.util.Map.Entry::getValue)
                    .findFirst()
                    .orElse("확인된 매체");
        } catch (Exception e) {
            return "확인된 매체";
        }
    }

    private boolean isWithinPeriod(NaverApiItem item, ZonedDateTime cutoff) {
        if (item.pubDate() == null) {
            return false;
        }
        try {
            ZonedDateTime published = ZonedDateTime.parse(item.pubDate(), DateTimeFormatter.RFC_1123_DATE_TIME);
            return !published.isBefore(cutoff);
        } catch (java.time.format.DateTimeParseException e) {
            log.warn("네이버 뉴스 pubDate 파싱 실패 - pubDate: {}", item.pubDate());
            return false;
        }
    }

    // 2026-08-11 수정 — 회사명은 원래 제목에서만 확인했다(2026-08-05, 본문까지 보면 사이드바·
    // 메뉴 잡음이 섞이는 문제가 실측돼 일부러 제목으로 좁힌 결정). 그런데 "[오늘의 주목주]
    // 한화오션... 코스피 반도체..."처럼 여러 종목을 한 번에 다루는 시황 기사는 제목엔 그날의
    // 대표 종목 하나만 걸고 나머지(삼성전자 포함)는 본문 요약에만 적는 경우가 많아, 이런 진짜
    // 관련 기사가 계속 걸러지는 게 실측 확인됨. topic 조건이 이미 함께 걸려 있어(둘 다 만족해야
    // 통과) 사이드바·메뉴 잡음이 topic까지 우연히 맞아떨어질 위험은 낮다고 판단해, 회사명도
    // topic과 동일하게 제목 또는 본문 요약 중 하나에만 있으면 인정하도록 완화한다.
    //
    // 2026-08-13 수정 — 위 완화 근거("topic이 항상 함께 걸린다")가 topic이 아예 없는 순수
    // 회사명 검색(예: "테슬라 관련 뉴스 찾아줘")에는 적용되지 않는데도, 코드가 topic 유무를
    // 구분하지 않고 항상 완화된 규칙을 쓰고 있었다. 그 결과 회사명이 본문 어딘가에 스쳐 지나가듯
    // 한 번만 언급된, 사실상 무관한 기사(라이브 테스트 실측: "낮엔 토요타, 밤엔 엔비디아…" 기사가
    // 미국 주식 24시간 거래를 다루며 예시로 "테슬라"를 딱 한 번 나열한 것뿐인데 "테슬라 관련
    // 뉴스"로 잡힘)까지 통과하는 문제가 발견됨. topic이 없을 때는 원래(2026-08-05) 규칙대로
    // 제목에만 회사명이 있어야 인정하도록 되돌리고, topic이 있을 때만(안전장치가 실제로 있을
    // 때만) 제목 또는 본문 요약 완화 규칙을 적용한다.
    private boolean isRelevant(NaverApiItem item, String companyName, String topic) {
        if (item.title() == null) {
            return false;
        }
        if (topic == null) {
            return NewsRelevanceMatcher.titleMatchesToken(item.title(), companyName);
        }
        boolean companyMatches = NewsRelevanceMatcher.titleMatchesToken(item.title(), companyName)
                || (item.description() != null && NewsRelevanceMatcher.titleMatchesToken(item.description(), companyName));
        if (!companyMatches) {
            return false;
        }
        return NewsRelevanceMatcher.topicMatchesAnyToken(item.title(), topic)
                || NewsRelevanceMatcher.topicMatchesAnyToken(item.description(), topic);
    }

    // 네이버 검색 응답의 title·description에는 검색어를 강조하는 <b> 태그와 HTML 엔티티(&quot; 등)가
    // 섞여 온다 — 매칭·최종 노출 둘 다에 방해가 되므로 걷어낸다.
    private NaverApiItem stripHtmlFields(NaverApiItem item) {
        return new NaverApiItem(stripHtml(item.title()), item.originallink(), item.link(), stripHtml(item.description()), item.pubDate());
    }

    private String stripHtml(String text) {
        if (text == null) {
            return null;
        }
        // 네이버 응답은 강조 태그(<b>)만 섞여 오고 그 외 HTML은 없다고 문서화돼 있어 태그
        // 제거는 그대로 두되, 엔티티 언이스케이프는 직접 replace 대신 HtmlUtils.htmlUnescape()로
        // 전체 HTML 엔티티 집합을 커버한다(수동 replace는 &quot;/&amp;/&lt;/&gt;/&#39;만 처리해
        // &nbsp; 같은 나머지 엔티티가 그대로 노출되는 문제가 있었다).
        return HtmlUtils.htmlUnescape(text.replace("<b>", "").replace("</b>", ""));
    }

    // 네이버 뉴스 검색 API 원본 응답 구조
    private record NaverApiResponse(String lastBuildDate, Integer total, Integer start, Integer display, List<NaverApiItem> items) {
    }

    private record NaverApiItem(String title, String originallink, String link, String description, String pubDate) {
    }
}
