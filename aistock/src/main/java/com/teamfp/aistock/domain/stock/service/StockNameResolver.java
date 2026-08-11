package com.teamfp.aistock.domain.stock.service;

import org.springframework.stereotype.Component;

import com.teamfp.aistock.domain.order.entity.Holding;
import com.teamfp.aistock.domain.order.entity.Order;
import com.teamfp.aistock.domain.order.repository.HoldingRepository;
import com.teamfp.aistock.domain.order.repository.OrderRepository;
import com.teamfp.aistock.domain.stock.entity.RecentViewed;
import com.teamfp.aistock.domain.stock.entity.Watchlist;
import com.teamfp.aistock.domain.stock.repository.RecentViewedRepository;
import com.teamfp.aistock.domain.stock.repository.WatchlistRepository;

import lombok.RequiredArgsConstructor;

/**
 * LsTickData.stockName이 항상 null로 오기 때문에, 우리 DB에 이미 등록된 종목명을 대신 찾아주는
 * 컴포넌트. schema.sql 13개 테이블에 별도 "종목 마스터" 테이블이 없어(KNOWN_ISSUES.md 1번 참고),
 * stockName이 저장돼있는 4개 테이블(watchlist/holdings/orders/recent_viewed)을 순서대로 조회한다.
 *
 * admin 도메인과 동일하게, 여러 도메인의 Repository를 직접 주입받아 조합하는 예외적 컴포넌트다
 * (CLAUDE.md 4번 — 여러 도메인을 가로지르는 조회는 중복 Repository를 만들지 않고 그대로 재사용).
 */
@Component
@RequiredArgsConstructor
public class StockNameResolver {

    private final WatchlistRepository watchlistRepository;
    private final HoldingRepository holdingRepository;
    private final OrderRepository orderRepository;
    private final RecentViewedRepository recentViewedRepository;

    /**
     * stockCode로 종목명을 찾는다. 4개 테이블 어디에도 없으면 null을 반환한다 — 아무도
     * 관심등록·매매·조회한 적 없는 신규/미거래 종목인 경우로, 근본 해결은 KNOWN_ISSUES.md 1번 참고.
     */
    public String resolveStockName(String stockCode) {
        return watchlistRepository.findFirstByStockCode(stockCode).map(Watchlist::getStockName)
                .or(() -> holdingRepository.findFirstByStockCode(stockCode).map(Holding::getStockName))
                .or(() -> orderRepository.findFirstByStockCode(stockCode).map(Order::getStockName))
                .or(() -> recentViewedRepository.findFirstByStockCode(stockCode).map(RecentViewed::getStockName))
                .orElse(null);
    }
}
