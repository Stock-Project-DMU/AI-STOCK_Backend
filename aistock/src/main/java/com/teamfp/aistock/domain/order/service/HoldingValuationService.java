package com.teamfp.aistock.domain.order.service;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.teamfp.aistock.domain.order.dto.HoldingValuationDto;
import com.teamfp.aistock.domain.order.entity.Holding;
import com.teamfp.aistock.domain.order.repository.HoldingRepository;
import com.teamfp.aistock.domain.stock.dto.StockPriceDto;
import com.teamfp.aistock.global.redis.RedisStockCacheService;

import lombok.RequiredArgsConstructor;

/**
 * 계좌 하나의 보유종목을 시세와 함께 평가하는 공용 로직. AccountService(수익률 계산)와
 * OrderService(보유종목 목록 조회) 둘 다 "보유종목 조회 → 시세 배치 조회 → 캐시 미스 시
 * 평단가로 대체"라는 동일한 절차가 필요해서, 각자 중복 구현하던 것을 이 서비스 하나로 모았다.
 * account 도메인이 order 도메인의 HoldingRepository/RedisStockCacheService를 직접 참조하지
 * 않고 이 서비스(order 도메인 소속)를 통해서만 접근하게 하기 위한 목적도 있다(코드리뷰 반영 —
 * 이전에는 AccountService가 HoldingRepository를 직접 주입받아 도메인 경계를 넘었었다).
 */
@Service
@RequiredArgsConstructor
public class HoldingValuationService {

    private final HoldingRepository holdingRepository;
    private final RedisStockCacheService redisStockCacheService;

    @Transactional(readOnly = true)
    public List<HoldingValuationDto> getHoldingValuations(Long accountId) {
        List<Holding> holdings = holdingRepository.findAllByAccountId(accountId);
        // holdings.uq_account_stock(account_id, stock_code)이 계좌 하나 안에서 종목코드 중복을
        // 막아주므로 distinct()는 불필요하다(항상 이미 유일함).
        Map<String, StockPriceDto> prices = redisStockCacheService.getStockPrices(
                holdings.stream().map(Holding::getStockCode).toList());

        return holdings.stream()
                .map(holding -> {
                    StockPriceDto priceDto = prices.get(holding.getStockCode());
                    Long currentPrice = priceDto != null ? priceDto.getCurrentPrice() : null;
                    return HoldingValuationDto.of(holding, currentPrice);
                })
                .toList();
    }

    /**
     * 계좌 여러 개(관리자 사용자 상세 — 유저당 최대 3개)의 보유종목을 계좌별로 N번 조회하지
     * 않고 한 번에 평가한다. uq_account_stock은 계좌 하나 안에서만 종목코드 중복을 막아주므로,
     * 계좌가 여러 개 섞이면 같은 종목코드가 여러 번 나올 수 있어 시세 배치 조회 전 distinct()가
     * 필요하다(AdminUserService 코드리뷰 반영, NAMING.md 8-17 참고).
     */
    @Transactional(readOnly = true)
    public List<HoldingValuationDto> getHoldingValuations(List<Long> accountIds) {
        List<Holding> holdings = holdingRepository.findAllByAccountIdIn(accountIds);
        Map<String, StockPriceDto> prices = redisStockCacheService.getStockPrices(
                holdings.stream().map(Holding::getStockCode).distinct().toList());

        return holdings.stream()
                .map(holding -> {
                    StockPriceDto priceDto = prices.get(holding.getStockCode());
                    Long currentPrice = priceDto != null ? priceDto.getCurrentPrice() : null;
                    return HoldingValuationDto.of(holding, currentPrice);
                })
                .toList();
    }
}
