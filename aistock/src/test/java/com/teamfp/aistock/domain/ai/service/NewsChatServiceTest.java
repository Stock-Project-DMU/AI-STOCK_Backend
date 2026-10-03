package com.teamfp.aistock.domain.ai.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import com.teamfp.aistock.domain.ai.dto.request.NewsChatRequest;
import com.teamfp.aistock.domain.ai.entity.NewsBriefingSetting;
import com.teamfp.aistock.domain.ai.repository.NewsBriefingSettingRepository;
import com.teamfp.aistock.infra.gemini.GeminiApiClient;
import com.teamfp.aistock.infra.gemini.dto.GeminiResponse;
import com.teamfp.aistock.infra.naver.NaverNewsApiClient;
import com.teamfp.aistock.infra.naver.dto.NaverNewsSearchResponse;

class NewsChatServiceTest {
    private final GeminiApiClient gemini = mock(GeminiApiClient.class);
    private final NaverNewsApiClient news = mock(NaverNewsApiClient.class);
    private final NewsBriefingSettingRepository settings = mock(NewsBriefingSettingRepository.class);
    private final NewsChatService service = new NewsChatService(gemini, news, settings);
    private GeminiResponse search() {
        return new GeminiResponse(null, 1, List.of(new GeminiResponse.FunctionCall("search_news", Map.of("companyName", "삼성전자", "periodDays", 7))));
    }
    private void articles() {
        when(news.search(any())).thenReturn(new NaverNewsSearchResponse(List.of(
                new NaverNewsSearchResponse.NaverNewsResult("삼성전자 실적", "영업이익 발표", "https://example.com/article", "2026-09-12", "테스트 언론사"))));
    }
    @Test void searchesAndReturnsGroundedSummaryWithSource() {
        articles();
        when(gemini.generate(any())).thenReturn(search(), new GeminiResponse("영업이익이 발표되었습니다.", 1), new GeminiResponse("SAFE", 1));
        var response = service.chat(1L, new NewsChatRequest("관련 뉴스 찾아줘", List.of(new NewsChatRequest.Turn("USER", "삼성전자가 궁금해"))));
        assertThat(response.content()).isEqualTo("영업이익이 발표되었습니다.");
        assertThat(response.sources()).hasSize(1);
        verify(news).search(argThat(request -> request.companyName().equals("삼성전자") && request.periodDays() == 7));
        verify(gemini).generate(argThat(request -> request.history().size() == 1 && !request.tools().isEmpty()));
    }
    @Test void searchedAtIsStandardIsoOffsetThatFitsColumn() {
        articles();
        when(gemini.generate(any())).thenReturn(search(), new GeminiResponse("영업이익이 발표되었습니다.", 1), new GeminiResponse("SAFE", 1));
        var response = service.chat(1L, new NewsChatRequest("관련 뉴스 찾아줘", List.of()));
        // searched_at 컬럼은 VARCHAR(40)이고 프론트는 new Date()로 파싱하므로 "[Asia/Seoul]" 같은 비표준 접미사가 없어야 한다.
        assertThat(response.searchedAt()).doesNotContain("[").endsWith("+09:00").hasSizeLessThanOrEqualTo(40);
        assertThatCode(() -> java.time.OffsetDateTime.parse(response.searchedAt())).doesNotThrowAnyException();
    }
    @Test void unsupportedSummaryFallsBackToRealArticles() {
        articles();
        when(gemini.generate(any())).thenReturn(search(), new GeminiResponse("근거 없는 주장", 1), new GeminiResponse("UNSAFE", 1));
        var response = service.chat(1L, new NewsChatRequest("뉴스 찾아줘", List.of()));
        assertThat(response.content()).doesNotContain("근거 없는 주장").contains("AI 요약을 제공하지 못해");
        assertThat(response.sources()).hasSize(1);
    }
    @Test void emptyResultsDoNotGenerateNews() {
        when(gemini.generate(any())).thenReturn(search());
        when(news.search(any())).thenReturn(new NaverNewsSearchResponse(List.of()));
        var response = service.chat(1L, new NewsChatRequest("뉴스 찾아줘", List.of()));
        assertThat(response.sources()).isEmpty();
        assertThat(response.content()).contains("찾지 못했습니다");
        verify(gemini, times(1)).generate(any());
    }
    @Test void ambiguousQueryAsksForTopicWithoutInventingNews() {
        when(gemini.generate(any())).thenReturn(new GeminiResponse("질문", 1));
        assertThat(service.chat(1L, new NewsChatRequest("안녕", List.of())).content()).contains("어떤 종목");
        verifyNoInteractions(news);
    }

    @Test void selectedOutletUsesFreshOutletSearch() {
        var setting = NewsBriefingSetting.builder().outletDomain("hankyung.com").build();
        when(settings.findByUserId(1L)).thenReturn(Optional.of(setting));
        when(gemini.generate(any())).thenReturn(search(), new GeminiResponse("한국경제 기사 요약", 1), new GeminiResponse("SAFE", 1));
        when(news.searchByOutlet(any(), eq("hankyung.com"))).thenReturn(new NaverNewsSearchResponse(List.of(
                new NaverNewsSearchResponse.NaverNewsResult("삼성전자 새 소식", "오늘 발표", "https://www.hankyung.com/article/1", "2026-09-26", "한국경제"))));

        var response = service.chat(1L, new NewsChatRequest("삼성전자 새 소식 알려줘", List.of()));

        assertThat(response.sources()).hasSize(1);
        assertThat(response.sources().get(0).outlet()).isEqualTo("한국경제");
        verify(news).searchByOutlet(argThat(request -> request.companyName().equals("삼성전자")), eq("hankyung.com"));
        verify(news, never()).search(any());
    }
}
