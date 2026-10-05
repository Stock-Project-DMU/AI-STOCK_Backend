package com.teamfp.aistock.domain.account.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    // 관리자 "전체 활동 기록"(AdminActivityService) — 한 페이지에 나온 셀프 충전·차감 기록을 회원 정보까지 한 번에 조회
    @Query("select t from AccountTransaction t join fetch t.account a join fetch a.user where t.transactionId in :transactionIds")
    List<AccountTransaction> findAllWithUserByTransactionIdIn(@Param("transactionIds") List<Long> transactionIds);

    // 관리자 계좌 상세의 누적 통계(feat/admin-improvements) — 계좌의 특정 유형 잔고 내역 합계(없으면 0).
    @Query("select coalesce(sum(t.amount), 0) from AccountTransaction t where t.account.accountId = :accountId and t.type in :types")
    long sumAmountByAccountIdAndTypeIn(@Param("accountId") Long accountId, @Param("types") Collection<AccountTransactionType> types);

    // 관리자 전체 활동 기록의 충전 요청 한 줄에 승인 입금(금액·처리 후 잔고)을 붙이는 데 쓴다(feat/admin-improvements).
    List<AccountTransaction> findAllByRelatedChargeRequestIdIn(List<Long> relatedChargeRequestIds);

    // 관리자 거래 상세의 잔고 변화(feat/admin-improvements) — 한 주문으로 생긴 잔고 내역(매수·매도·수수료·환불)을 기록 순서대로.
    List<AccountTransaction> findAllByRelatedOrderIdOrderByCreatedAtAscTransactionIdAsc(Long relatedOrderId);
}
