package com.teamfp.aistock.domain.stock.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.teamfp.aistock.domain.stock.entity.RecentViewed;

public interface RecentViewedRepository extends JpaRepository<RecentViewed, Long> {

    @Query("select r from RecentViewed r where r.user.userId = :userId order by r.viewedAt desc")
    List<RecentViewed> findAllByUserIdOrderByViewedAtDesc(@Param("userId") Long userId);

    @Query("select r from RecentViewed r where r.user.userId = :userId and r.stockCode = :stockCode")
    Optional<RecentViewed> findByUserIdAndStockCode(@Param("userId") Long userId, @Param("stockCode") String stockCode);

    @Modifying
    @Query("delete from RecentViewed r where r.user.userId = :userId")
    void deleteByUserId(@Param("userId") Long userId);
}
