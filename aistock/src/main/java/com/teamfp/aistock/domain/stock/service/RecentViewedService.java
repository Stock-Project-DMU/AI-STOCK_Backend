package com.teamfp.aistock.domain.stock.service;

import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.teamfp.aistock.domain.stock.dto.StockPriceDto;
import com.teamfp.aistock.domain.stock.dto.response.RecentViewedResponse;
import com.teamfp.aistock.domain.stock.entity.RecentViewed;
import com.teamfp.aistock.domain.stock.repository.RecentViewedRepository;
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
public class RecentViewedService {

    private final RecentViewedRepository recentViewedRepository;
    private final UserRepository userRepository;
    // WatchlistService와 같은 이유로 global/redis 서비스를 직접 조회한다(feature/stock-price 미구현).
    private final RedisStockCacheService redisStockCacheService;

    // recent_viewed.uq_user_stock_view — WatchlistService.UNIQUE_CONSTRAINT_NAME과 같은 이유로,
    // DataIntegrityViolationException의 원인이 이 제약인지 메시지로 정확히 구분한다.
    private static final String UNIQUE_CONSTRAINT_NAME = "uq_user_stock_view";

    @Transactional(readOnly = true)
    public List<RecentViewedResponse> getMyRecentViewed(Long userId) {
        return recentViewedRepository.findAllByUserIdOrderByViewedAtDesc(userId).stream()
                .map(RecentViewedResponse::from)
                .toList();
    }

    /**
     * 최근 본 종목 기록. 이미 같은 종목을 본 기록이 있으면(uq_user_stock_view) 새 행을 추가하지
     * 않고 viewedAt만 지금 시각으로 갱신해야 한다(schema.sql 11번 테이블 주석). RecentViewed는
     * viewedAt 외에 바뀌는 필드가 없어 엔티티를 다시 save()해도 Hibernate가 변경분 없음으로
     * 판단해 UPDATE를 스킵하므로, RecentViewedRepository.touchViewedAt()으로 UPDATE 쿼리를
     * 직접 날려 갱신한다(과거에는 delete 후 재삽입 방식이었는데, RecentViewed가
     * @GeneratedValue(IDENTITY)라 save()가 즉시 INSERT를 실행해버려서 아직 flush 안 된 DELETE와
     * 충돌해 uq_user_stock_view 위반이 나는 버그가 있었다).
     *
     * touchViewedAt()이 갱신한 행이 없으면(처음 보는 종목) 그때만 현재가 캐시에서 종목명을 얻어
     * 새 행을 만든다 — 재조회 경로에서는 이미 저장된 종목명이 있으니 Redis를 불필요하게 조회하지
     * 않는다.
     *
     * touchViewedAt() 조회와 save() 사이에 동시에 같은 종목을 기록하는 요청이 끼어들면 둘 다
     * "처음 보는 종목"으로 판단해 동시에 insert를 시도할 수 있고, 이 중 하나는 uq_user_stock_view
     * 위반으로 DataIntegrityViolationException이 save() 시점(IDENTITY라 즉시 flush됨)에 터진다.
     * 이 경우 먼저 삽입된 쪽이 이미 원하는 결과(해당 종목 기록 존재)를 만들어 놓은 것이므로,
     * touchViewedAt()을 한 번 더 호출해 viewedAt만 지금 시각으로 갱신하고 넘어간다.
     */
    @Transactional
    public void recordView(Long userId, String stockCode) {
        int updatedRows = recentViewedRepository.touchViewedAt(userId, stockCode);
        if (updatedRows > 0) {
            return;
        }

        StockPriceDto priceDto = redisStockCacheService.getStockPrice(stockCode);
        if (priceDto == null) {
            throw new CustomException(ErrorCode.STOCK_PRICE_NOT_AVAILABLE);
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        RecentViewed recentViewed = RecentViewed.builder()
                .user(user)
                .stockCode(stockCode)
                .stockName(priceDto.getStockName())
                .build();
        try {
            recentViewedRepository.save(recentViewed);
        } catch (DataIntegrityViolationException e) {
            if (!isUniqueConstraintViolation(e)) {
                throw e;
            }
            log.info("최근 본 종목 동시 기록 요청 충돌(userId={}, stockCode={}) - viewedAt만 갱신", userId, stockCode);
            recentViewedRepository.touchViewedAt(userId, stockCode);
        }
    }

    private boolean isUniqueConstraintViolation(DataIntegrityViolationException e) {
        Throwable cause = e.getMostSpecificCause();
        return cause.getMessage() != null && cause.getMessage().contains(UNIQUE_CONSTRAINT_NAME);
    }
}
