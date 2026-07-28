package com.teamfp.aistock.domain.order.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.teamfp.aistock.domain.account.entity.Account;
import com.teamfp.aistock.domain.order.dto.HoldingValuationDto;
import com.teamfp.aistock.domain.order.entity.Holding;
import com.teamfp.aistock.domain.order.repository.HoldingRepository;
import com.teamfp.aistock.domain.stock.dto.StockPriceDto;
import com.teamfp.aistock.domain.user.entity.Role;
import com.teamfp.aistock.domain.user.entity.User;
import com.teamfp.aistock.global.redis.RedisStockCacheService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * feature/mypage-profit 코드리뷰 반영 — AccountService.getProfit()/OrderService.getMyHoldings()가
 * 각자 복붙하던 "보유종목 조회 → 시세 배치 조회 → 평단가 폴백" 로직을 이 서비스로 모았다.
 * 이 서비스 하나만 제대로 검증하면 두 호출부는 결과를 그대로 옮기기만 하면 되므로,
 * 캐시 히트/미스 계산은 여기서 집중적으로 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class HoldingValuationServiceTest {

    @Mock
    private HoldingRepository holdingRepository;

    @Mock
    private RedisStockCacheService redisStockCacheService;

    private HoldingValuationService holdingValuationService;

    private static final Long ACCOUNT_ID = 100L;
    private static final String STOCK_CODE = "005930";

    private Account account;

    @BeforeEach
    void setUp() {
        holdingValuationService = new HoldingValuationService(holdingRepository, redisStockCacheService);

        User user = User.builder()
                .loginId("tester")
                .name("테스터")
                .role(Role.USER)
                .isActive(true)
                .build();

        account = Account.builder()
                .user(user)
                .accountName("계좌A")
                .accountNumber("ACC-0001")
                .openedAt(LocalDate.now())
                .baseBalance(10_000_000L)
                .balance(10_000_000L)
                .build();
    }

    private Holding holdingOf(int quantity, long avgPrice) {
        return Holding.builder()
                .account(account)
                .stockCode(STOCK_CODE)
                .stockName("삼성전자")
                .quantity(quantity)
                .avgPrice(avgPrice)
                .build();
    }

    @Test
    @DisplayName("보유종목이 없으면 빈 목록을 반환한다")
    void getHoldingValuations_empty() {
        when(holdingRepository.findAllByAccountId(ACCOUNT_ID)).thenReturn(List.of());
        when(redisStockCacheService.getStockPrices(List.of())).thenReturn(Map.of());

        List<HoldingValuationDto> result = holdingValuationService.getHoldingValuations(ACCOUNT_ID);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("시세 캐시가 있으면 캐시의 현재가를 사용한다")
    void getHoldingValuations_priceCacheHit() {
        Holding holding = holdingOf(10, 50_000L);
        when(holdingRepository.findAllByAccountId(ACCOUNT_ID)).thenReturn(List.of(holding));
        when(redisStockCacheService.getStockPrices(List.of(STOCK_CODE))).thenReturn(Map.of(
                STOCK_CODE, StockPriceDto.builder().stockCode(STOCK_CODE).stockName("삼성전자").currentPrice(60_000L).build()
        ));

        List<HoldingValuationDto> result = holdingValuationService.getHoldingValuations(ACCOUNT_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).stockCode()).isEqualTo(STOCK_CODE);
        assertThat(result.get(0).quantity()).isEqualTo(10);
        assertThat(result.get(0).currentPrice()).isEqualTo(60_000L);
    }

    @Test
    @DisplayName("시세 캐시가 비어있으면 평단가로 대체한다")
    void getHoldingValuations_priceCacheMissFallsBackToAvgPrice() {
        Holding holding = holdingOf(10, 50_000L);
        when(holdingRepository.findAllByAccountId(ACCOUNT_ID)).thenReturn(List.of(holding));
        when(redisStockCacheService.getStockPrices(List.of(STOCK_CODE))).thenReturn(Map.of());

        List<HoldingValuationDto> result = holdingValuationService.getHoldingValuations(ACCOUNT_ID);

        assertThat(result.get(0).currentPrice()).isEqualTo(50_000L);
    }
}
