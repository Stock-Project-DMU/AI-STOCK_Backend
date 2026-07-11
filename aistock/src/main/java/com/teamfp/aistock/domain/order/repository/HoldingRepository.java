package com.teamfp.aistock.domain.order.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.teamfp.aistock.domain.order.entity.Holding;

public interface HoldingRepository extends JpaRepository<Holding, Long> {

    @Query("select h from Holding h where h.account.accountId = :accountId")
    List<Holding> findAllByAccountId(@Param("accountId") Long accountId);

    @Query("select h from Holding h where h.account.accountId = :accountId and h.stockCode = :stockCode")
    Optional<Holding> findByAccountIdAndStockCode(@Param("accountId") Long accountId, @Param("stockCode") String stockCode);
}
