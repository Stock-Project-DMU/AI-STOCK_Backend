package com.teamfp.aistock.domain.account.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.teamfp.aistock.domain.account.entity.AccountTransaction;

public interface AccountTransactionRepository extends JpaRepository<AccountTransaction, Long> {

    Page<AccountTransaction> findAllByAccount_AccountId(Long accountId, Pageable pageable);
}
