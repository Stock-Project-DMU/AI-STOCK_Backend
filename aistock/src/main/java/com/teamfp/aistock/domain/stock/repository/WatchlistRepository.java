package com.teamfp.aistock.domain.stock.repository;

import java.util.List;

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

    @Modifying
    @Query("delete from Watchlist w where w.user.userId = :userId and w.stockCode = :stockCode")
    void deleteByUserIdAndStockCode(@Param("userId") Long userId, @Param("stockCode") String stockCode);

    @Modifying
    @Query("delete from Watchlist w where w.user.userId = :userId")
    void deleteByUserId(@Param("userId") Long userId);
}
