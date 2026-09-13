package com.teamfp.aistock.domain.stock.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import com.teamfp.aistock.domain.stock.dto.StockPriceDto;
import com.teamfp.aistock.domain.stock.repository.WatchlistRepository;
import com.teamfp.aistock.domain.user.entity.User;
import com.teamfp.aistock.domain.user.repository.UserRepository;
import com.teamfp.aistock.global.exception.CustomException;

class WatchlistServiceTest {
    private final WatchlistRepository repository = mock(WatchlistRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final StockQuoteService quotes = mock(StockQuoteService.class);
    private final StockSubscriptionManager subscriptions = mock(StockSubscriptionManager.class);
    private final WatchlistService service = new WatchlistService(repository, users, quotes, subscriptions);

    @Test void addsUsingResolvedQuoteAndRemovesSubscription() {
        when(quotes.getStockPrice("005930")).thenReturn(StockPriceDto.builder()
                .stockName("삼성전자").currentPrice(70000).build());
        when(users.findById(1L)).thenReturn(Optional.of(User.builder().build()));
        service.addWatchlist(1L, "005930");
        verify(repository).save(argThat(item -> "삼성전자".equals(item.getStockName()) && "005930".equals(item.getStockCode())));
        verify(subscriptions).increaseWatchlistSubscription("005930");
        when(repository.deleteByUserIdAndStockCode(1L, "005930")).thenReturn(1);
        service.removeWatchlist(1L, "005930");
        verify(subscriptions).decreaseWatchlistSubscription("005930");
    }

    @Test void unavailableQuoteDoesNotPersistInvalidStock() {
        assertThatThrownBy(() -> service.addWatchlist(1L, "005930")).isInstanceOf(CustomException.class);
        verify(repository, never()).save(any());
        verifyNoInteractions(subscriptions);
    }
}
