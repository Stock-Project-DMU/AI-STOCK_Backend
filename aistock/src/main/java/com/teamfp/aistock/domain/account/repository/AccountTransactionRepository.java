package com.teamfp.aistock.domain.account.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.teamfp.aistock.domain.account.entity.AccountTransaction;
import com.teamfp.aistock.domain.account.entity.AccountTransactionType;

public interface AccountTransactionRepository extends JpaRepository<AccountTransaction, Long> {

    Page<AccountTransaction> findAllByAccount_AccountId(Long accountId, Pageable pageable);

    // ChargeRequestService.getChargeHistory()용 — 직접 충전(AUTO_CHARGE)·관리자 충전(ADMIN_CHARGE) 원장.
    List<AccountTransaction> findAllByAccount_AccountIdAndTypeIn(Long accountId, Collection<AccountTransactionType> types);

    // AccountService.payMonthlyInterest()의 같은 달 이자 중복 지급 방지용.
    boolean existsByAccount_AccountIdAndTypeAndCreatedAtGreaterThanEqual(Long accountId, AccountTransactionType type,
            LocalDateTime createdAt);

    // AccountInterestJob.payMissedMonthlyInterest()용 — 이번 달 정기 이자 지급이 이미 됐는지(계좌 무관).
    boolean existsByTypeAndCreatedAtGreaterThanEqual(AccountTransactionType type, LocalDateTime createdAt);
}
