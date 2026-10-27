package com.teamfp.aistock.domain.stock.service;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import com.teamfp.aistock.infra.marketdata.dto.IndustryPriceDto;
import com.teamfp.aistock.infra.marketdata.dto.RankingItemDto;

import tools.jackson.databind.json.JsonMapper;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 메인 화면 실시간 갱신 — 지수 1초·순위 2초, 바뀐 것만, 정규장에만 보내는지 확인한다(2026-10-13). */
class MarketOverviewBroadcastServiceTest {

    private final MarketQueryService marketQueryService = mock(MarketQueryService.class);
    private final MarketSessionService marketSessionService = mock(MarketSessionService.class);
    private final SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
    private MarketOverviewBroadcastService service;

    @BeforeEach
    void setUp() {
        service = new MarketOverviewBroadcastService(marketQueryService, marketSessionService, messagingTemplate,
                JsonMapper.builder().build());
        when(marketSessionService.isBroadcastWindow()).thenReturn(true);
    }

    private static List<IndustryPriceDto> indexes(double kospi) {
        return List.of(new IndustryPriceDto("001", "코스피", kospi, 0.5));
    }

    private static List<RankingItemDto> rankings(long price) {
        return List.of(RankingItemDto.builder().rank(1).stockCode("005930").stockName("삼성전자").price(price).build());
    }

    @Test
    @DisplayName("지수는 매초, 순위(정렬 5종)는 2초마다 보낸다 — 값이 바뀔 때")
    void sendsIndexesEverySecondAndRankingsEveryTwoSeconds() {
        when(marketQueryService.getIndexes()).thenReturn(indexes(3000), indexes(3001), indexes(3002));
        // volume 순위만 두 번째 계산에서 값이 바뀌고, 나머지 정렬은 그대로다.
        when(marketQueryService.getRankings(anyString(), eq(true))).thenReturn(rankings(70000));
        when(marketQueryService.getRankings("volume", true)).thenReturn(rankings(70000), rankings(70100));

        service.broadcast(); // 1초: 지수 + 순위
        service.broadcast(); // 2초: 지수만
        service.broadcast(); // 3초: 지수 + 순위

        verify(messagingTemplate, times(3)).convertAndSend(eq(MarketOverviewBroadcastService.INDEX_TOPIC), any(Object.class));
        verify(marketQueryService, times(10)).getRankings(anyString(), eq(true)); // 5종 × 2회
        // 3초째 순위는 첫 정렬(volume)만 값이 바뀌어(70000 → 70100) 그것만 다시 보내고, 나머지 4종은 같아서 1번씩만 보낸다.
        verify(messagingTemplate, times(2)).convertAndSend(eq(MarketOverviewBroadcastService.RANKING_TOPIC_PREFIX + "volume"), any(Object.class));
        for (String sort : MarketOverviewBroadcastService.RANKING_SORTS.subList(1, MarketOverviewBroadcastService.RANKING_SORTS.size())) {
            verify(messagingTemplate).convertAndSend(eq(MarketOverviewBroadcastService.RANKING_TOPIC_PREFIX + sort), any(Object.class));
        }
    }

    @Test
    @DisplayName("지난번에 보낸 값과 같으면 다시 보내지 않는다")
    void skipsUnchangedPayload() {
        when(marketQueryService.getIndexes()).thenReturn(indexes(3000));
        when(marketQueryService.getRankings(anyString(), eq(true))).thenReturn(rankings(70000));

        service.broadcast();
        service.broadcast();
        service.broadcast();

        verify(messagingTemplate, times(1)).convertAndSend(eq(MarketOverviewBroadcastService.INDEX_TOPIC), any(Object.class));
        verify(messagingTemplate, times(1)).convertAndSend(eq(MarketOverviewBroadcastService.RANKING_TOPIC_PREFIX + "volume"), any(Object.class));
    }

    @Test
    @DisplayName("정규장(마감 후 60초 포함)이 아니면 계산도 전송도 하지 않는다")
    void doesNothingOutsideBroadcastWindow() {
        when(marketSessionService.isBroadcastWindow()).thenReturn(false);

        service.broadcast();

        verifyNoInteractions(marketQueryService, messagingTemplate);
    }

    @Test
    @DisplayName("조회가 한 번 실패해도 예외를 밖으로 던지지 않고 다음 주기에 다시 보낸다")
    void failureDoesNotStopNextTick() {
        when(marketQueryService.getIndexes()).thenThrow(new IllegalStateException("파일 읽기 실패")).thenReturn(indexes(3000));
        when(marketQueryService.getRankings(anyString(), anyBoolean())).thenReturn(rankings(70000));

        service.broadcast();
        verify(messagingTemplate, never()).convertAndSend(eq(MarketOverviewBroadcastService.INDEX_TOPIC), any(Object.class));
        service.broadcast();
        verify(messagingTemplate).convertAndSend(eq(MarketOverviewBroadcastService.INDEX_TOPIC), any(Object.class));
    }
}
