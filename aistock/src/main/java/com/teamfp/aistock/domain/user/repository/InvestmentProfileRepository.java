package com.teamfp.aistock.domain.user.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.teamfp.aistock.domain.user.entity.InvestmentProfile;

public interface InvestmentProfileRepository extends JpaRepository<InvestmentProfile, Long> {

    @Query("select p from InvestmentProfile p where p.user.userId = :userId")
    Optional<InvestmentProfile> findByUserId(@Param("userId") Long userId);

    @Modifying
    @Query("delete from InvestmentProfile p where p.user.userId = :userId")
    void deleteByUserId(@Param("userId") Long userId);
}
