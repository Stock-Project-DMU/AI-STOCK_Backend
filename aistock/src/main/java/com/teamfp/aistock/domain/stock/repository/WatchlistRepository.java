package com.teamfp.aistock.domain.stock.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.teamfp.aistock.domain.stock.entity.Watchlist;

public interface WatchlistRepository extends JpaRepository<Watchlist, Long> {

    @Query("select w from Watchlist w where w.user.userId = :userId")
    List<Watchlist> findAllByUserId(@Param("userId") Long userId);

    @Query("select case when count(w) > 0 then true else false end from Watchlist w "
            + "where w.user.userId = :userId and w.stockCode = :stockCode")
    boolean existsByUserIdAndStockCode(@Param("userId") Long userId, @Param("stockCode") String stockCode);

    // v14, feature/stock-price: 반환 타입을 void에서 int(삭제된 행 수)로 변경 — WatchlistService.
    // removeWatchlist()가 실제로 삭제가 일어났는지 알아야 StockSubscriptionManager.
    // decreaseWatchlistSubscription() 호출 여부를 판단할 수 있다.
    @Modifying
    @Query("delete from Watchlist w where w.user.userId = :userId and w.stockCode = :stockCode")
    int deleteByUserIdAndStockCode(@Param("userId") Long userId, @Param("stockCode") String stockCode);

    @Modifying
    @Query("delete from Watchlist w where w.user.userId = :userId")
    void deleteByUserId(@Param("userId") Long userId);

    // 4주차 feature/stock-price StockNameResolver용 — stockCode만으로 이미 누군가 관심등록한 적
    // 있는 종목의 stockName을 찾는다(유저 무관, 어느 행이든 하나만 있으면 됨).
    Optional<Watchlist> findFirstByStockCode(String stockCode);
}
