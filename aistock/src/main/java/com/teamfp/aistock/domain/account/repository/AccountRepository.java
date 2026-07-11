package com.teamfp.aistock.domain.account.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.teamfp.aistock.domain.account.entity.Account;

public interface AccountRepository extends JpaRepository<Account, Long> {

    @Query("select a from Account a where a.user.userId = :userId")
    Optional<Account> findByUserId(@Param("userId") Long userId);

    Optional<Account> findByAccountNumber(String accountNumber);

    @Modifying
    @Query("delete from Account a where a.user.userId = :userId")
    void deleteByUserId(@Param("userId") Long userId);
}
