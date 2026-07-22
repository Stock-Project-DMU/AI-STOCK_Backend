package com.teamfp.aistock.domain.stock.service;

import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
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
import lombok.extern.slf4j.Slf4j;

@Slf4j
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

    // watchlist.uq_user_stock — 같은 유저·종목의 관심종목 등록이 동시에 두 건 이상 요청될 때
    // 걸리는 유니크 제약. HoldingSettlementService.isUniqueConstraintViolation()과 같은 이유로
    // DataIntegrityViolationException의 원인이 이 제약인지 메시지로 정확히 구분한다 — stockName이
    // 컬럼 길이를 초과하는 등 재시도해도 해소되지 않는 다른 저장 실패까지 "이미 등록됨"으로
    // 오인해 삼켜버리면 안 되기 때문이다.
    private static final String UNIQUE_CONSTRAINT_NAME = "uq_user_stock";

    @Transactional(readOnly = true)
    public List<WatchlistResponse> getMyWatchlist(Long userId) {
        return watchlistRepository.findAllByUserId(userId).stream()
                .map(WatchlistResponse::from)
                .toList();
    }

    /**
     * 관심종목 추가. 이미 추가되어 있으면(uq_user_stock) 에러 없이 그대로 둔다 —
     * "관심종목 등록"은 토글성 액션이라 중복 요청을 실패로 취급할 이유가 없다.
     * existsBy 조회와 save() 사이에 동시에 같은 종목을 추가하는 요청이 끼어들면
     * uq_user_stock을 위반하는 DataIntegrityViolationException이 save() 시점(IDENTITY라 즉시
     * flush됨)에 터질 수 있는데, 이 역시 위와 같은 이유로 실패로 취급하지 않고 무시한다.
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
        try {
            watchlistRepository.save(watchlist);
        } catch (DataIntegrityViolationException e) {
            if (!isUniqueConstraintViolation(e)) {
                throw e;
            }
            log.info("관심종목 동시 추가 요청 충돌(userId={}, stockCode={}) - 이미 등록된 것으로 처리", userId, stockCode);
        }
    }

    private boolean isUniqueConstraintViolation(DataIntegrityViolationException e) {
        Throwable cause = e.getMostSpecificCause();
        return cause.getMessage() != null && cause.getMessage().contains(UNIQUE_CONSTRAINT_NAME);
    }

    /**
     * 관심종목 삭제. 이미 없는 종목을 삭제 요청해도 에러 없이 그대로 둔다(DELETE의 멱등성).
     */
    @Transactional
    public void removeWatchlist(Long userId, String stockCode) {
        watchlistRepository.deleteByUserIdAndStockCode(userId, stockCode);
    }
}
