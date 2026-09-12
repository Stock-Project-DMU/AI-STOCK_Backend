package com.teamfp.aistock.domain.ai.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.teamfp.aistock.domain.ai.dto.request.NewsChatRequest;
import com.teamfp.aistock.infra.gemini.GeminiApiClient;
import com.teamfp.aistock.infra.gemini.dto.GeminiResponse;
import com.teamfp.aistock.infra.naver.NaverNewsApiClient;
import com.teamfp.aistock.infra.naver.dto.NaverNewsSearchResponse;

class NewsChatServiceTest {
    private final GeminiApiClient gemini = mock(GeminiApiClient.class);
    private final NaverNewsApiClient news = mock(NaverNewsApiClient.class);
    private final NewsChatService service = new NewsChatService(gemini, news);
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
        var response = service.chat(new NewsChatRequest("관련 뉴스 찾아줘", List.of(new NewsChatRequest.Turn("USER", "삼성전자가 궁금해"))));
        assertThat(response.content()).isEqualTo("영업이익이 발표되었습니다.");
        assertThat(response.sources()).hasSize(1);
        verify(news).search(argThat(request -> request.companyName().equals("삼성전자") && request.periodDays() == 7));
        verify(gemini).generate(argThat(request -> request.history().size() == 1 && !request.tools().isEmpty()));
    }
    @Test void unsupportedSummaryFallsBackToRealArticles() {
        articles();
        when(gemini.generate(any())).thenReturn(search(), new GeminiResponse("근거 없는 주장", 1), new GeminiResponse("UNSAFE", 1));
        var response = service.chat(new NewsChatRequest("뉴스 찾아줘", List.of()));
        assertThat(response.content()).doesNotContain("근거 없는 주장").contains("AI 요약을 제공하지 못해");
        assertThat(response.sources()).hasSize(1);
    }
    @Test void emptyResultsDoNotGenerateNews() {
        when(gemini.generate(any())).thenReturn(search());
        when(news.search(any())).thenReturn(new NaverNewsSearchResponse(List.of()));
        var response = service.chat(new NewsChatRequest("뉴스 찾아줘", List.of()));
        assertThat(response.sources()).isEmpty();
        assertThat(response.content()).contains("찾지 못했습니다");
        verify(gemini, times(1)).generate(any());
    }
    @Test void ambiguousQueryAsksForTopicWithoutInventingNews() {
        when(gemini.generate(any())).thenReturn(new GeminiResponse("질문", 1));
        assertThat(service.chat(new NewsChatRequest("안녕", List.of())).content()).contains("어떤 종목");
        verifyNoInteractions(news);
    }
}
