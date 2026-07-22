package com.teamfp.aistock.domain.stock.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.teamfp.aistock.domain.stock.dto.StockPriceDto;
import com.teamfp.aistock.domain.stock.dto.response.WatchlistResponse;
import com.teamfp.aistock.domain.stock.entity.Watchlist;
import com.teamfp.aistock.domain.stock.repository.WatchlistRepository;
import com.teamfp.aistock.domain.user.entity.User;
import com.teamfp.aistock.domain.user.repository.UserRepository;
import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;
import com.teamfp.aistock.global.redis.RedisStockCacheService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class WatchlistService {

    private final WatchlistRepository watchlistRepository;
    private final UserRepository userRepository;
    // OrderService.createLimitOrder()와 같은 이유로 feature/stock-price(StockService)를 거치지
    // 않고 global/redis 서비스를 직접 조회한다 — 이 프로젝트는 종목 마스터 테이블이 따로 없어
    // (schema.sql 13개 테이블 기준) stockName을 얻을 수 있는 유일한 소스가 LS증권 tick 캐시
    // (stock:price)이기 때문이다.
    private final RedisStockCacheService redisStockCacheService;

    @Transactional(readOnly = true)
    public List<WatchlistResponse> getMyWatchlist(Long userId) {
        return watchlistRepository.findAllByUserId(userId).stream()
                .map(WatchlistResponse::from)
                .toList();
    }

    /**
     * 관심종목 추가. 이미 추가되어 있으면(uq_user_stock) 에러 없이 그대로 둔다 —
     * "관심종목 등록"은 토글성 액션이라 중복 요청을 실패로 취급할 이유가 없다.
     */
    @Transactional
    public void addWatchlist(Long userId, String stockCode) {
        if (watchlistRepository.existsByUserIdAndStockCode(userId, stockCode)) {
            return;
        }

        StockPriceDto priceDto = redisStockCacheService.getStockPrice(stockCode);
        if (priceDto == null) {
            throw new CustomException(ErrorCode.STOCK_PRICE_NOT_AVAILABLE);
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        Watchlist watchlist = Watchlist.builder()
                .user(user)
                .stockCode(stockCode)
                .stockName(priceDto.getStockName())
                .build();
        watchlistRepository.save(watchlist);
    }

    /**
     * 관심종목 삭제. 이미 없는 종목을 삭제 요청해도 에러 없이 그대로 둔다(DELETE의 멱등성).
     */
    @Transactional
    public void removeWatchlist(Long userId, String stockCode) {
        watchlistRepository.deleteByUserIdAndStockCode(userId, stockCode);
    }
}
