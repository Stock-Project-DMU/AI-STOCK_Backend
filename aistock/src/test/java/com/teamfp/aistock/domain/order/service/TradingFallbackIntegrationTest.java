package com.teamfp.aistock.domain.order.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import com.teamfp.aistock.domain.account.entity.Account;
import com.teamfp.aistock.domain.account.repository.AccountRepository;
import com.teamfp.aistock.domain.notification.service.NotificationService;
import com.teamfp.aistock.domain.order.dto.request.CreateOrderRequest;
import com.teamfp.aistock.domain.order.entity.*;
import com.teamfp.aistock.domain.stock.service.*;
import com.teamfp.aistock.domain.user.entity.*;
import com.teamfp.aistock.domain.user.repository.UserRepository;
import com.teamfp.aistock.global.redis.RedisPendingOrderService;
import com.teamfp.aistock.global.redis.RedisStockCacheService;
import com.teamfp.aistock.infra.ls.LsMarketDataApiClient;
import com.teamfp.aistock.infra.ls.dto.LsCurrentPriceDetailDto;

/** Uses a temporary user and transaction rollback; no real quote, notification or trade is sent. */
@SpringBootTest(properties = {"ls.mode=mock"})
@Transactional(isolation = Isolation.READ_COMMITTED)
class TradingFallbackIntegrationTest {
    @Autowired OrderService orders;
    @Autowired WatchlistService watchlist;
    @Autowired RecentViewedService recent;
    @Autowired UserRepository users;
    @Autowired AccountRepository accounts;
    @MockitoBean RedisStockCacheService redis;
    @MockitoBean LsMarketDataApiClient ls;
    @MockitoBean RedisPendingOrderService pending;
    @MockitoBean NotificationService notifications;
    @MockitoBean StockSubscriptionManager subscriptions;

    @Test void watchlistMarketTradeAndLimitCancellationWorkWithoutLiveCache() {
        String suffix = Long.toString(System.nanoTime());
        User user = users.save(User.builder().loginId("trade-check-" + suffix).name("주문검증")
                .role(Role.USER).isActive(true).build());
        Account account = accounts.save(Account.builder().user(user).accountName("임시 검증")
                .accountNumber("T" + suffix).openedAt(LocalDate.now()).baseBalance(1000000).balance(1000000).build());
        when(ls.getCurrentPrice("005930")).thenReturn(Optional.of(LsCurrentPriceDetailDto.builder()
                .stockCode("005930").stockName("삼성전자").currentPrice(70000).build()));

        watchlist.addWatchlist(user.getUserId(), "005930");
        assertThat(watchlist.getMyWatchlist(user.getUserId())).hasSize(1);
        watchlist.removeWatchlist(user.getUserId(), "005930");
        assertThat(watchlist.getMyWatchlist(user.getUserId())).isEmpty();

        var buy = orders.createMarketOrder(user.getUserId(), new CreateOrderRequest(account.getAccountId(), "005930", OrderType.BUY, 2, PriceType.MARKET, 1));
        assertThat(buy.status()).isEqualTo(OrderStatus.EXECUTED);
        assertThat(buy.execPrice()).isEqualTo(70000);
        assertThat(account.getBalance()).isEqualTo(860000);
        var sell = orders.createMarketOrder(user.getUserId(), new CreateOrderRequest(account.getAccountId(), "005930", OrderType.SELL, 1, PriceType.MARKET, 0));
        assertThat(sell.status()).isEqualTo(OrderStatus.EXECUTED);
        assertThat(account.getBalance()).isEqualTo(930000);

        // An unowned stock exercises the limit-order name fallback too.
        when(ls.getCurrentPrice("000660")).thenReturn(Optional.of(LsCurrentPriceDetailDto.builder()
                .stockCode("000660").stockName("SK하이닉스").currentPrice(100000).build()));
        var limit = orders.createLimitOrder(user.getUserId(), new CreateOrderRequest(account.getAccountId(), "000660", OrderType.BUY, 1, PriceType.LIMIT, 90000));
        assertThat(limit.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(account.getBalance()).isEqualTo(840000);
        assertThat(account.getFrozenBalance()).isEqualTo(90000);
        orders.cancelOrder(user.getUserId(), limit.orderId());
        assertThat(account.getBalance()).isEqualTo(930000);
        assertThat(account.getFrozenBalance()).isZero();
        recent.recordView(user.getUserId(), "005930");
        recent.recordView(user.getUserId(), "005930");
        assertThat(recent.getMyRecentViewed(user.getUserId())).hasSize(1);
        assertThat(recent.getMyRecentViewed(user.getUserId()).getFirst().stockName()).isEqualTo("삼성전자");
    }
}
