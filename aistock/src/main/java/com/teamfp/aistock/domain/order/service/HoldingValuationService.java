package com.teamfp.aistock.domain.order.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.teamfp.aistock.domain.order.dto.HoldingValuationDto;
import com.teamfp.aistock.domain.order.entity.Holding;
import com.teamfp.aistock.domain.order.repository.HoldingRepository;
import com.teamfp.aistock.domain.stock.dto.StockPriceDto;
import com.teamfp.aistock.domain.stock.service.StockQuoteService;
import com.teamfp.aistock.global.redis.RedisStockCacheService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 계좌 하나의 보유종목을 시세와 함께 평가하는 공용 로직. AccountService(수익률 계산)와
 * OrderService(보유종목 목록 조회) 둘 다 "보유종목 조회 → 시세 배치 조회 → 캐시 미스 시
 * 마지막 시세 조회 → 그것도 실패하면 평단가로 대체"라는 동일한 절차가 필요해서, 각자 중복 구현하던 것을 이 서비스 하나로 모았다.
 * account 도메인이 order 도메인의 HoldingRepository/RedisStockCacheService를 직접 참조하지
 * 않고 이 서비스(order 도메인 소속)를 통해서만 접근하게 하기 위한 목적도 있다(코드리뷰 반영 —
 * 이전에는 AccountService가 HoldingRepository를 직접 주입받아 도메인 경계를 넘었었다).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HoldingValuationService {

    private final HoldingRepository holdingRepository;
    private final RedisStockCacheService redisStockCacheService;
    // Redis 실시간 시세 캐시(TTL 5초)가 비어 있는 종목의 마지막 시세 조회용. market-data.mode에 따라
    // mock이면 로컬 시세 JSON, real이면 외부 시세 데이터 현재가 조회로 알아서 갈라진다.
    private final StockQuoteService stockQuoteService;

    @Transactional(readOnly = true)
    public List<HoldingValuationDto> getHoldingValuations(Long accountId) {
        List<Holding> holdings = holdingRepository.findAllByAccountId(accountId);
        // holdings.uq_account_stock(account_id, stock_code)이 계좌 하나 안에서 종목코드 중복을
        // 막아주므로 distinct()는 불필요하다(항상 이미 유일함).
        return valuate(holdings, holdings.stream().map(Holding::getStockCode).toList());
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
        return valuate(holdings, holdings.stream().map(Holding::getStockCode).distinct().toList());
    }

    /**
     * 시세 배치 조회 + 캐시 미스 시 평단가 폴백 매핑. 두 getHoldingValuations() 오버로드가
     * 조회 쿼리·시세 조회용 종목코드 목록(distinct 여부)만 다르고 나머지는 동일했던 것을
     * 이 메서드로 모았다.
     */
    private List<HoldingValuationDto> valuate(List<Holding> holdings, List<String> stockCodesForPriceLookup) {
        Map<String, StockPriceDto> prices = new HashMap<>(redisStockCacheService.getStockPrices(stockCodesForPriceLookup));
        // 실시간 tick이 없는 종목(구독 중이 아니거나 장 마감 후)은 캐시가 비어 평단가로 평가되면 평가손익이
        // 항상 0원으로 고정된다. 그런 종목만 마지막 시세(장 마감 후에는 종가)를 한 번 더 조회한다.
        for (String stockCode : stockCodesForPriceLookup) {
            if (prices.get(stockCode) == null) {
                StockPriceDto lastQuote = fetchLastQuote(stockCode);
                if (lastQuote != null) {
                    prices.put(stockCode, lastQuote);
                }
            }
        }

        return holdings.stream()
                .map(holding -> {
                    StockPriceDto priceDto = prices.get(holding.getStockCode());
                    Long currentPrice = priceDto != null ? priceDto.getCurrentPrice() : null;
                    return HoldingValuationDto.of(holding, currentPrice);
                })
                .toList();
    }

    /**
     * 마지막 시세 조회. 시세 제공처 장애로 실패해도 보유종목 목록 전체가 실패하면 안 되므로, 예외를
     * 삼키고 null을 돌려줘 그 종목만 평단가로 평가되게 한다.
     */
    private StockPriceDto fetchLastQuote(String stockCode) {
        try {
            return stockQuoteService.getStockPrice(stockCode);
        } catch (RuntimeException e) {
            log.warn("[HoldingValuationService] 마지막 시세 조회 실패, 평단가로 평가 - stockCode: {}", stockCode, e);
            return null;
        }
    }
}
