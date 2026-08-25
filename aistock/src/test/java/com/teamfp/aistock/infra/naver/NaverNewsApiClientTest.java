package com.teamfp.aistock.infra.naver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.teamfp.aistock.infra.naver.dto.NaverNewsSearchRequest;
import com.teamfp.aistock.infra.naver.dto.NaverNewsSearchResponse;

/**
 * NaverNewsApiClient 단위 테스트.
 *
 * <p>TavilyApiClientTest와 관련성 판단 원칙(1단계 companyName 항상 강제, 2단계 topic은 있을
 * 때만 추가로 강제, 3단계 periodDays는 없으면 기본 30일)은 동일하게 검증하되, 네이버 고유의
 * 처리(서버 측 매체 제한 파라미터가 없어 클라이언트에서 직접 신뢰 매체로 거르는 것, 서버 측
 * 기간 필터 파라미터가 없어 pubDate로 직접 거르는 것, <b> 태그·HTML 엔티티 제거)도 함께
 * 검증한다.</p>
 */
class NaverNewsApiClientTest {

    private static final String API_URL = "https://test-naver/search/v1/news";
    private static final String CLIENT_ID = "test-client-id";
    private static final String CLIENT_SECRET = "test-client-secret";

    private MockRestServiceServer mockServer;
    private NaverNewsApiClient naverNewsApiClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();

        naverNewsApiClient = new NaverNewsApiClient(builder);
        ReflectionTestUtils.setField(naverNewsApiClient, "clientId", CLIENT_ID);
        ReflectionTestUtils.setField(naverNewsApiClient, "clientSecret", CLIENT_SECRET);
        ReflectionTestUtils.setField(naverNewsApiClient, "apiUrl", API_URL);
    }

    // 신뢰 매체 목록(SECURITIES_NEWS_DOMAINS)에 있는 도메인 - 대부분의 관련성 테스트에서
    // originallink로 이 도메인을 써야 매체 필터를 통과한다.
    private static final String TRUSTED_LINK = "https://www.hankyung.com/article/1";
    private static final String UNTRUSTED_LINK = "https://blog.naver.com/somebody/1";

    private String pubDate(int daysAgo) {
        return ZonedDateTime.now(ZoneOffset.ofHours(9)).minusDays(daysAgo)
                .format(DateTimeFormatter.RFC_1123_DATE_TIME);
    }

    private String item(String title, String originallink, String description, int daysAgo) {
        return """
                {"title":"%s","originallink":"%s","link":"%s","description":"%s","pubDate":"%s"}
                """.formatted(title, originallink, originallink, description, pubDate(daysAgo));
    }

    @Test
    @DisplayName("제목에 회사명이 없는 무관한 결과는 걸러내고, 관련 있는 결과만 남긴다")
    void search_filtersOutResultsWhoseTitleDoesNotContainCompanyName() {
        mockServer.expect(requestTo(startsWith(API_URL)))
                .andRespond(withSuccess("""
                        {"total":2,"items":[
                          %s,
                          %s
                        ]}""".formatted(
                        item("주한미국대사 인터뷰", TRUSTED_LINK, "무관한 내용", 1),
                        item("골드만삭스, 삼성전자 강력매수 권고", TRUSTED_LINK, "삼성전자 관련 실제 내용", 1)),
                        MediaType.APPLICATION_JSON));

        NaverNewsSearchResponse response = naverNewsApiClient.search(new NaverNewsSearchRequest("삼성전자", null, null));

        assertThat(response.results()).hasSize(1);
        assertThat(response.results().get(0).title()).contains("골드만삭스");
    }

    @Test
    @DisplayName("사용자가 출처를 물었을 때 답할 수 있도록 결과에 실제 언론사명(outlet)을 채워 돌려준다")
    void search_resolvesOutletNameFromTrustedDomain() {
        mockServer.expect(requestTo(startsWith(API_URL)))
                .andRespond(withSuccess("""
                        {"total":1,"items":[
                          %s
                        ]}""".formatted(item("삼성전자, 3분기 실적 발표", TRUSTED_LINK, "실적 관련 내용", 1)),
                        MediaType.APPLICATION_JSON));

        NaverNewsSearchResponse response = naverNewsApiClient.search(new NaverNewsSearchRequest("삼성전자", null, null));

        assertThat(response.results()).hasSize(1);
        assertThat(response.results().get(0).outlet()).isEqualTo("한국경제");
    }

    @Test
    @DisplayName("모든 결과의 제목에 회사명이 없으면 빈 결과를 반환한다")
    void search_returnsEmptyWhenNoResultTitleContainsCompanyName() {
        mockServer.expect(requestTo(startsWith(API_URL)))
                .andRespond(withSuccess("""
                        {"total":1,"items":[
                          %s
                        ]}""".formatted(item("주한미국대사 인터뷰", TRUSTED_LINK, "무관한 내용", 1)),
                        MediaType.APPLICATION_JSON));

        NaverNewsSearchResponse response = naverNewsApiClient.search(new NaverNewsSearchRequest("삼성전자", null, null));

        assertThat(response.results()).isEmpty();
    }

    @Test
    @DisplayName("2단계(topic)가 있으면, 제목·본문 어디에도 주제어가 없는 무관한 기사는 걸러낸다")
    void search_withTopic_filtersOutCompanyNameOnlyMatch() {
        mockServer.expect(requestTo(startsWith(API_URL)))
                .andRespond(withSuccess("""
                        {"total":2,"items":[
                          %s,
                          %s
                        ]}""".formatted(
                        item("삼성전자 노조, 임금협상 결렬", TRUSTED_LINK, "노조와 사측의 임금 협상이 최종 결렬됐다", 1),
                        item("삼성전자, 3분기 실적 시장 예상 상회", TRUSTED_LINK, "실적 관련 실제 내용", 1)),
                        MediaType.APPLICATION_JSON));

        NaverNewsSearchResponse response = naverNewsApiClient.search(new NaverNewsSearchRequest("삼성전자", "실적", null));

        assertThat(response.results()).hasSize(1);
        assertThat(response.results().get(0).title()).contains("시장 예상 상회");
    }

    @Test
    @DisplayName("제목엔 주제어가 없어도 본문(description)에 있으면 관련 있다고 판단한다")
    void search_withTopic_matchesWhenTopicOnlyInDescription() {
        mockServer.expect(requestTo(startsWith(API_URL)))
                .andRespond(withSuccess("""
                        {"total":1,"items":[
                          %s
                        ]}""".formatted(item("LG전자, 세탁건조기 라인업 확대", TRUSTED_LINK,
                                "LG전자가 신제품 대용량 프리미엄 세탁건조기를 출시했다", 1)),
                        MediaType.APPLICATION_JSON));

        NaverNewsSearchResponse response = naverNewsApiClient.search(new NaverNewsSearchRequest("LG전자", "신제품", null));

        assertThat(response.results()).hasSize(1);
    }

    @Test
    @DisplayName("2단계(topic)가 없으면(막연한 질문), 회사명만 일치해도 관련 있다고 판단한다")
    void search_withoutTopic_companyNameAloneIsEnough() {
        mockServer.expect(requestTo(startsWith(API_URL)))
                .andRespond(withSuccess("""
                        {"total":1,"items":[
                          %s
                        ]}""".formatted(item("삼성전자 노조, 임금협상 결렬", TRUSTED_LINK, "노무 이슈", 1)),
                        MediaType.APPLICATION_JSON));

        NaverNewsSearchResponse response = naverNewsApiClient.search(new NaverNewsSearchRequest("삼성전자", null, null));

        assertThat(response.results()).hasSize(1);
    }

    @Test
    @DisplayName("주제(topic)까지 맞는 기사가 제목·본문 어디에도 없으면, 회사명만 맞아도 억지로 보여주지 않고 관련 없음으로 처리한다")
    void search_withTopic_noStrictMatch_doesNotFallBackToUnrelatedCompanyNews() {
        mockServer.expect(requestTo(startsWith(API_URL)))
                .andRespond(withSuccess("""
                        {"total":2,"items":[
                          %s,
                          %s
                        ]}""".formatted(
                        item("삼성전자, 신형 스마트안경 공개", TRUSTED_LINK, "신제품 관련 내용", 1),
                        item("주한미국대사 인터뷰", TRUSTED_LINK, "무관한 내용", 1)),
                        MediaType.APPLICATION_JSON));

        NaverNewsSearchResponse response = naverNewsApiClient.search(new NaverNewsSearchRequest("삼성전자", "실적", null));

        assertThat(response.results()).isEmpty();
    }

    @Test
    @DisplayName("2026-08-06 정책 변경 - 접미사를 뗀 축약명이 2자뿐이면(예: '삼성') 더 이상 매칭에 쓰지 않는다")
    void search_doesNotMatchTwoCharacterAbbreviation() {
        // 예전엔 "삼성전자"→"삼성" 2자 축약도 허용했지만, 계열사가 많은 그룹에서 "삼성"만으로
        // 매칭하면 완전히 다른 계열사 기사까지 걸려버리는 문제가 실측으로 확인돼(아래
        // search_doesNotConfuseSiblingAffiliatesSharingGroupPrefix 테스트 참고) 최소 3자
        // 기준으로 좁혔다. "삼성 첫 스마트안경" 같은 기사는 이제 정확한 전체 표기("삼성전자")가
        // 있어야만 잡힌다. 2026-08-11 회사명 매칭이 본문 요약까지 보도록 완화된 뒤에도 이
        // 테스트의 취지(2자 축약만으로는 안 잡힘)가 유지되도록, description에는 "삼성전자"
        // 전체 표기를 넣지 않는다 — 넣으면 본문 요약 매칭 경로로 우회해서 이 테스트가 검증하려는
        // 정책과 무관하게 통과해버린다.
        mockServer.expect(requestTo(startsWith(API_URL)))
                .andRespond(withSuccess("""
                        {"total":1,"items":[
                          %s
                        ]}""".formatted(item("삼성 첫 스마트안경, 무게는 덜고 AI는 더했다", TRUSTED_LINK, "웨어러블 신제품 관련 실제 내용", 1)),
                        MediaType.APPLICATION_JSON));

        NaverNewsSearchResponse response = naverNewsApiClient.search(new NaverNewsSearchRequest("삼성전자", null, null));

        assertThat(response.results()).isEmpty();
    }

    @Test
    @DisplayName("실제 라이브 테스트로 확인된 사례 - 그룹 계열사를 검색했는데 같은 그룹의 무관한 다른 계열사 기사가 뒤섞이지 않는다")
    void search_doesNotConfuseSiblingAffiliatesSharingGroupPrefix() {
        // 2026-08-06 라이브 테스트 - "삼성생명" 실적을 물었는데, 접미사("생명")를 뗀 "삼성"만으로
        // 매칭하는 바람에 삼성전기·삼성증권 등 완전히 다른 계열사 기사가 5건 중 4건이나 섞여
        // 나온 실제 사례를 재현한다.
        mockServer.expect(requestTo(startsWith(API_URL)))
                .andRespond(withSuccess("""
                        {"total":2,"items":[
                          %s,
                          %s
                        ]}""".formatted(
                        item("삼성전기, 반도체 훈풍에 장중 12%대 급등", TRUSTED_LINK, "삼성전기 관련 내용", 1),
                        item("뉴욕·런던법인 재인수한 삼성생명…글로벌 영토 확장", TRUSTED_LINK, "삼성생명 관련 내용", 1)),
                        MediaType.APPLICATION_JSON));

        NaverNewsSearchResponse response = naverNewsApiClient.search(new NaverNewsSearchRequest("삼성생명", null, null));

        assertThat(response.results()).hasSize(1);
        assertThat(response.results().get(0).title()).contains("삼성생명");
    }

    @Test
    @DisplayName("제목·주제 모두 일치해도 신뢰 매체 목록 밖의 도메인이면 걸러낸다")
    void search_filtersOutUntrustedDomain() {
        mockServer.expect(requestTo(startsWith(API_URL)))
                .andRespond(withSuccess("""
                        {"total":2,"items":[
                          %s,
                          %s
                        ]}""".formatted(
                        item("삼성전자 개인 블로그 후기", UNTRUSTED_LINK, "개인적인 감상평", 1),
                        item("삼성전자, 3분기 실적 발표", TRUSTED_LINK, "실적 관련 실제 내용", 1)),
                        MediaType.APPLICATION_JSON));

        NaverNewsSearchResponse response = naverNewsApiClient.search(new NaverNewsSearchRequest("삼성전자", null, null));

        assertThat(response.results()).hasSize(1);
        assertThat(response.results().get(0).link()).isEqualTo(TRUSTED_LINK);
    }

    @Test
    @DisplayName("기본 기간(30일)보다 오래된 기사는 걸러내고, 기간 이내 기사만 남긴다")
    void search_withoutPeriodDays_filtersOutArticlesOlderThanDefaultThirtyDays() {
        mockServer.expect(requestTo(startsWith(API_URL)))
                .andRespond(withSuccess("""
                        {"total":2,"items":[
                          %s,
                          %s
                        ]}""".formatted(
                        item("삼성전자, 지난달 실적 발표", TRUSTED_LINK, "실적 관련 내용", 40),
                        item("삼성전자, 이번주 실적 발표", TRUSTED_LINK, "실적 관련 내용", 5)),
                        MediaType.APPLICATION_JSON));

        NaverNewsSearchResponse response = naverNewsApiClient.search(new NaverNewsSearchRequest("삼성전자", null, null));

        assertThat(response.results()).hasSize(1);
        assertThat(response.results().get(0).title()).contains("이번주");
    }

    @Test
    @DisplayName("periodDays를 사용자가 직접 지정하면 그 기준으로 기간을 거른다")
    void search_withPeriodDays_filtersUsingGivenValue() {
        mockServer.expect(requestTo(startsWith(API_URL)))
                .andRespond(withSuccess("""
                        {"total":2,"items":[
                          %s,
                          %s
                        ]}""".formatted(
                        item("삼성전자, 5일 전 실적 발표", TRUSTED_LINK, "실적 관련 내용", 5),
                        item("삼성전자, 오늘 실적 발표", TRUSTED_LINK, "실적 관련 내용", 0)),
                        MediaType.APPLICATION_JSON));

        NaverNewsSearchResponse response = naverNewsApiClient.search(new NaverNewsSearchRequest("삼성전자", null, 3));

        assertThat(response.results()).hasSize(1);
        assertThat(response.results().get(0).title()).contains("오늘");
    }

    @Test
    @DisplayName("제목·본문에 섞여 온 <b> 태그와 HTML 엔티티를 제거해서 돌려준다")
    void search_stripsHtmlTagsAndEntitiesFromTitleAndDescription() {
        mockServer.expect(requestTo(startsWith(API_URL)))
                .andRespond(withSuccess("""
                        {"total":1,"items":[
                          {"title":"<b>삼성전자</b>, &quot;3분기 실적&quot; 발표","originallink":"%s","link":"%s","description":"영업이익 &amp; 매출 모두 &lt;증가&gt;","pubDate":"%s"}
                        ]}""".formatted(TRUSTED_LINK, TRUSTED_LINK, pubDate(1)),
                        MediaType.APPLICATION_JSON));

        NaverNewsSearchResponse response = naverNewsApiClient.search(new NaverNewsSearchRequest("삼성전자", null, null));

        assertThat(response.results()).hasSize(1);
        assertThat(response.results().get(0).title()).isEqualTo("삼성전자, \"3분기 실적\" 발표");
        assertThat(response.results().get(0).description()).isEqualTo("영업이익 & 매출 모두 <증가>");
    }

    @Test
    @DisplayName("items가 없으면(null) 빈 응답을 반환한다")
    void search_handlesNullItems() {
        mockServer.expect(requestTo(startsWith(API_URL)))
                .andRespond(withSuccess("""
                        {"total":0,"items":null}""", MediaType.APPLICATION_JSON));

        NaverNewsSearchResponse response = naverNewsApiClient.search(new NaverNewsSearchRequest("삼성전자", null, null));

        assertThat(response.results()).isEmpty();
    }

    @Test
    @DisplayName("관련성 있는 결과가 5개보다 많으면 최대 5개까지만 반환한다")
    void search_limitsResultsToFive() {
        StringBuilder items = new StringBuilder();
        for (int i = 1; i <= 6; i++) {
            if (i > 1) {
                items.append(",");
            }
            items.append(item("삼성전자 실적 관련 기사 " + i, TRUSTED_LINK, "실적 내용", 1));
        }
        mockServer.expect(requestTo(startsWith(API_URL)))
                .andRespond(withSuccess("{\"total\":6,\"items\":[" + items + "]}", MediaType.APPLICATION_JSON));

        NaverNewsSearchResponse response = naverNewsApiClient.search(new NaverNewsSearchRequest("삼성전자", null, null));

        assertThat(response.results()).hasSize(5);
    }

    @Test
    @DisplayName("실제 검색 결과 도메인 감사(2026-08-06)로 추가한 매체도 신뢰 매체로 인식한다")
    void search_recognizesDomainsAddedFromLiveAudit() {
        mockServer.expect(requestTo(startsWith(API_URL)))
                .andRespond(withSuccess("""
                        {"total":1,"items":[
                          %s
                        ]}""".formatted(item("삼성전자, 3분기 실적 발표", "https://www.ajunews.com/view/1", "실적 관련 내용", 1)),
                        MediaType.APPLICATION_JSON));

        NaverNewsSearchResponse response = naverNewsApiClient.search(new NaverNewsSearchRequest("삼성전자", null, null));

        assertThat(response.results()).hasSize(1);
    }

    @Test
    @DisplayName("실제 라이브 호출로 확인된 사례 - 응답 Content-Type이 application/json이 아니라 text/plain이어도 정상 파싱한다")
    void search_parsesResponseEvenWhenContentTypeIsTextPlain() {
        // 네이버 뉴스 검색 API는 format=json으로 요청해도 실제로는 응답 Content-Type을
        // text/plain;charset=UTF-8로 내려준다(2026-08-06 라이브 호출로 확인) - 이 테스트가 없을 때는
        // MockRestServiceServer가 항상 application/json을 명시해서 이 문제를 못 잡아냈다.
        mockServer.expect(requestTo(startsWith(API_URL)))
                .andRespond(withSuccess("""
                        {"total":1,"items":[
                          %s
                        ]}""".formatted(item("삼성전자, 3분기 실적 발표", TRUSTED_LINK, "실적 관련 내용", 1)),
                        MediaType.TEXT_PLAIN));

        NaverNewsSearchResponse response = naverNewsApiClient.search(new NaverNewsSearchRequest("삼성전자", null, null));

        assertThat(response.results()).hasSize(1);
        assertThat(response.results().get(0).title()).contains("실적 발표");
    }

    @Test
    @DisplayName("검색어·페이지 파라미터·NCP 인증 헤더를 예상대로 보낸다")
    void search_sendsExpectedQueryParamsAndHeaders() {
        // MockRestServiceServer는 실제로 전송된 percent-encoded 쿼리 문자열과 비교하므로,
        // UriComponentsBuilder와 동일한 RFC 3986 규칙(공백은 +가 아니라 %20)으로 직접 인코딩해서
        // 기대값을 만든다 - URLEncoder.encode()는 application/x-www-form-urlencoded 규칙이라
        // 공백을 +로 인코딩해 그대로 쓰면 어긋난다.
        // 2026-08-11 수정 — topic("실적")이 있어도 검색어 자체는 companyName만 보낸다. 예전엔
        // "삼성전자 실적"으로 합쳐서 보냈는데, 라이브 테스트에서 회사명+주제어를 합친 검색어가
        // 네이버 쪽에서 오히려 진짜 관련 기사를 후보에서 빠뜨리는 사례가 실측돼(예: "삼성전자
        // 이란" 검색은 후보 100건 중 "삼성전자"가 제목에 든 게 0건, "삼성전자" 단독 검색은
        // 실제 관련 기사가 있었음), topic은 검색어가 아니라 결과를 받은 뒤 필터링 단계에서만
        // 쓰도록 바꿨다(NaverNewsApiClient.search() 참고). 같은 이유로 topic이 있으면 정렬도
        // 정확도순(sim) 대신 최신순(date)을 쓴다.
        String encodedQuery = URLEncoder.encode("삼성전자", StandardCharsets.UTF_8).replace("+", "%20");
        mockServer.expect(requestTo(startsWith(API_URL)))
                .andExpect(queryParam("query", encodedQuery))
                .andExpect(queryParam("display", "100"))
                .andExpect(queryParam("sort", "date"))
                .andExpect(queryParam("format", "json"))
                .andExpect(header("X-NCP-APIGW-API-KEY-ID", CLIENT_ID))
                .andExpect(header("X-NCP-APIGW-API-KEY", CLIENT_SECRET))
                .andRespond(withSuccess("""
                        {"total":0,"items":[]}""", MediaType.APPLICATION_JSON));

        naverNewsApiClient.search(new NaverNewsSearchRequest("삼성전자", "실적", null));

        mockServer.verify();
    }
}
