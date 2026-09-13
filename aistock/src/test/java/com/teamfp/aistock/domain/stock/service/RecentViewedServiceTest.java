package com.teamfp.aistock.domain.stock.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import com.teamfp.aistock.domain.stock.dto.StockPriceDto;
import com.teamfp.aistock.domain.stock.repository.RecentViewedRepository;
import com.teamfp.aistock.domain.user.entity.User;
import com.teamfp.aistock.domain.user.repository.UserRepository;
import com.teamfp.aistock.global.exception.CustomException;

class RecentViewedServiceTest {
    private final RecentViewedRepository repository = mock(RecentViewedRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final StockQuoteService quotes = mock(StockQuoteService.class);
    private final RecentViewedService service = new RecentViewedService(repository, users, quotes);

    @Test void firstVisitPersistsResolvedStockName() {
        when(quotes.getStockPrice("005930")).thenReturn(StockPriceDto.builder().stockName("삼성전자").currentPrice(70000).build());
        when(users.findById(1L)).thenReturn(Optional.of(User.builder().build()));
        service.recordView(1L, "005930");
        verify(repository).save(argThat(item -> "005930".equals(item.getStockCode()) && "삼성전자".equals(item.getStockName())));
    }

    @Test void repeatVisitUpdatesTimestampWithoutQuoteOrDuplicateInsert() {
        when(repository.touchViewedAt(1L, "005930")).thenReturn(1);
        service.recordView(1L, "005930");
        verifyNoInteractions(quotes, users);
        verify(repository, never()).save(any());
    }

    @Test void unresolvedStockIsNotSaved() {
        assertThatThrownBy(() -> service.recordView(1L, "005930")).isInstanceOf(CustomException.class);
        verify(repository, never()).save(any());
    }
}
