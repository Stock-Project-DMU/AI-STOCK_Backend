package com.teamfp.aistock.infra.naver;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    // 언론사 도메인 → 한글명 매핑은 2026-08-24부터 NewsRelevanceMatcher.OUTLET_NAMES로 옮겼다
    // (feature/ai-news가 "언론사 이름으로 선택 → 도메인 변환"이라는 반대 방향으로도 이 매핑이
    // 필요해져서, 이 클래스에만 있던 private map을 공용 위치로 승격했다). matchesDomain()도
    // 같은 이유로 NewsRelevanceMatcher.matchesDomain()으로 이동했다.

    // feature/ai-news(맞춤형 뉴스 브리핑) 전용 — 특정 언론사 하나의 "종합 시황" 기사를 폭넓게
    // 받아오기 위한 고정 검색어 목록. 사용자가 입력하는 값이 아니라 내부적으로만 쓰는 미끼
    // 검색어다. 네이버 뉴스 검색 API는 검색어 없이는 호출 자체가 안 되고, 언론사를 직접
    // 지정하는 파라미터도 없어(클래스 상단 javadoc 참고) 이렇게 넉넉한 후보군을 받아온 뒤
    // 도메인으로 걸러내는 방식을 쓴다.
    //
    // "증시" 하나만 쓰면 놓치는 언론사가 있었다(2026-08-24 실측) — 조선비즈는 "증시"로는 0건이
    // 나왔지만, 같은 날 "코스피"로 검색하니 "[마켓뷰] 삼성전자 급락에 코스피 6700선 아래로"
    // 같은 진짜 시황 기사가 나왔다. 검색어별로 네이버가 반환하는 후보 집합 자체가 달라지기
    // 때문(단순히 더 많이 가져온다고 해결되는 문제가 아니었음). 그래서 여러 동의어로 순서대로
    // 시도한다 — 앞쪽에서 이미 채워지면 뒤쪽 검색어는 호출되지 않는다(아래 searchByOutlet()의
    // early-exit 참고).
    private static final List<String> GENERAL_MARKET_QUERIES = List.of("증시", "코스피", "주가", "코스닥");
    // 언론사 몫(MAX_RESULTS)을 채울 때까지 최대 몇 페이지(페이지당 100건)까지 추가로 조회할지.
    // 실측(2026-08-24)으로 5페이지(500건) 안에서는 항상 오늘 날짜 기사만 나오는 것을 확인했다
    // (그 이상은 날짜가 넘어갈 위험이 있어 시도하지 않음) — 위 searchByOutlet() 주석 참고.
    private static final int MAX_OUTLET_PAGES = 5;

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

    // feature/ai-news(맞춤형 뉴스 브리핑) 전용 — search()와 달리 회사명이 없으므로 제목 매칭
    // 관련성 필터(isRelevant())를 아예 적용하지 않는다. 대신 처음부터 "이 언론사인지"만으로
    // 좁혀서 거른다 — search()처럼 신뢰 도메인 29곳 전체에서 먼저 5건으로 잘라낸 뒤 그중
    // 특정 언론사만 골라내는 순서로 하면, 사용자가 고른 언론사 기사가 실제로 있어도 그 5건
    // 안에 못 들어 결과가 0건이 되는 문제가 있다(2026-08-24 설계 논의). 그래서 도메인 필터를
    // 가장 먼저 적용하고, 그 다음에야 최대 개수로 자른다. outletDomain은 호출 측
    // (AiNewsService)이 NewsRelevanceMatcher.OUTLET_NAMES에 등록된 값인지 미리 검증해서
    // 넘긴다고 가정한다.
    //
    // 페이지네이션(2026-08-24 추가) — 실측 결과, "증시" 검색은 하루에만 500건 넘게 나올 만큼
    // 흔한 검색어라 첫 페이지(100건, 최신순) 안에 특정 언론사 기사가 하나도 없는 경우가
    // 29곳 중 16곳이나 됐다(그 언론사가 그날 기사가 없어서가 아니라, 다른 언론사 기사에
    // 밀려 100건 밖으로 밀려난 것 — 500건까지 확인해보니 15곳은 실제로 기사가 있었고, 전부
    // 오늘 날짜였다). 그렇다고 매번 5페이지(500건)를 다 가져오면 네이버 API 호출이 5배로
    // 늘어 낭비이므로, 이 언론사 몫(MAX_RESULTS)을 채우면 그 즉시 멈추는 방식으로 최소한만
    // 호출한다 — 이미 첫 페이지에서 다 채워지는 언론사(한국경제 등 대부분)는 종전과 동일하게
    // 1번만 호출되고, 게재량이 적은 언론사만 필요한 만큼 추가 페이지를 더 가져간다.
    public NaverNewsSearchResponse searchByOutlet(String outletDomain) {
        List<NaverNewsResult> collected = new ArrayList<>();
        Set<String> seenLinks = new HashSet<>();
        // 통신사(연합뉴스 등)가 같은 기사를 시간대별로 갱신 재배포하면 link는 다른데 제목은
        // 완전히 동일한 경우가 실측 확인됐다(2026-08-24, "삼전·닉스 동반 하락에 코스피 3%
        // 하락"이 5건 중 4건). link 중복 제거만으로는 못 잡아서 제목 기준도 추가한다 — 이
        // Set에 걸려 collected에 못 들어간 기사는 MAX_RESULTS 카운트에도 안 잡히므로, 중복을
        // 빼는 대신 다른 검색어/페이지를 더 뒤져 진짜 다른 기사로 채운다(아래 early-exit 조건
        // collected.size() < MAX_RESULTS가 자연스럽게 이를 보장한다).
        Set<String> seenTitles = new HashSet<>();

        for (String query : GENERAL_MARKET_QUERIES) {
            for (int page = 0; page < MAX_OUTLET_PAGES && collected.size() < MAX_RESULTS; page++) {
                int start = 1 + page * RAW_FETCH_COUNT;
                NaverApiResponse response = ExternalApiInvoker.call(() -> restClient.get()
                                .uri(uriBuilder -> uriBuilder
                                        .scheme("https")
                                        .host(hostOf(apiUrl))
                                        .path(pathOf(apiUrl))
                                        .queryParam("query", query)
                                        .queryParam("display", RAW_FETCH_COUNT)
                                        .queryParam("start", start)
                                        .queryParam("sort", SORT_DATE)
                                        .queryParam("format", FORMAT_JSON)
                                        .build())
                                .header("X-NCP-APIGW-API-KEY-ID", clientId)
                                .header("X-NCP-APIGW-API-KEY", clientSecret)
                                .retrieve()
                                .body(NaverApiResponse.class),
                        "네이버 뉴스 검색 API 호출 실패(언론사별 시황 조회) - outletDomain: {}, query: {}, start: {}", outletDomain, query, start);

                if (response == null || response.items() == null || response.items().isEmpty()) {
                    break; // 이 검색어로는 네이버가 더 줄 결과가 없음 — 다음 검색어로 넘어간다.
                }

                response.items().stream()
                        .filter(item -> matchesOutlet(item, outletDomain))
                        .map(this::stripHtmlFields)
                        // 언론사 필터만으로는 진짜 시황 기사인지 보장 못 한다 — 실제로 신뢰 매체에서도
                        // "증시"와 무관한 기사가 섞여 들어오는 걸 실측 확인해 추가한 관련성 필터
                        // (2026-08-24). 본문 요약까지 포함해 확인하면 "본문 한 줄에만 증시 얘기가 스친"
                        // 기사(예: 도핑 스캔들 기사에 "그 회사가 최근 상장했다"는 한 줄만 있는 경우)까지
                        // 통과해버려, "시황 브리핑이라면 제목부터 증시 얘기여야 한다"는 판단에 따라
                        // 제목만 확인하도록 좁혔다(2026-08-24 사용자 확정) — search()의 회사명 매칭이
                        // 제목만 보는 것과 같은 원칙.
                        .filter(item -> NewsRelevanceMatcher.isMarketRelevant(item.title()))
                        .map(item -> new NaverNewsResult(item.title(), item.description(), item.link(), item.pubDate(), resolveOutletName(item)))
                        // 검색어를 여러 개 시도하다 보면 같은 기사가 두 검색어에 걸쳐 다시 나올 수
                        // 있다(예: "코스피"와 "주가" 둘 다에 걸리는 기사) — link 기준으로 중복 제거.
                        .filter(item -> seenLinks.add(item.link()))
                        // link는 다른데 제목이 완전히 같은 재배포 기사도 제외(위 seenTitles 선언부 주석 참고).
                        .filter(item -> seenTitles.add(item.title()))
                        .forEach(collected::add);
            }
            if (collected.size() >= MAX_RESULTS) {
                break; // 이 언론사 몫을 채웠으면 나머지 검색어는 시도할 필요 없다.
            }
        }

        List<NaverNewsResult> filtered = collected.stream().limit(MAX_RESULTS).toList();
        return new NaverNewsSearchResponse(filtered);
    }

    private boolean matchesOutlet(NaverApiItem item, String outletDomain) {
        String source = item.originallink() != null && !item.originallink().isBlank() ? item.originallink() : item.link();
        if (source == null) {
            return false;
        }
        try {
            String host = java.net.URI.create(source).getHost();
            return host != null && NewsRelevanceMatcher.matchesDomain(host, outletDomain);
        } catch (IllegalArgumentException e) {
            return false;
        }
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
            return NewsRelevanceMatcher.SECURITIES_NEWS_DOMAINS.stream().anyMatch(domain -> NewsRelevanceMatcher.matchesDomain(host, domain));
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
            return NewsRelevanceMatcher.OUTLET_NAMES.entrySet().stream()
                    .filter(entry -> NewsRelevanceMatcher.matchesDomain(host, entry.getKey()))
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
