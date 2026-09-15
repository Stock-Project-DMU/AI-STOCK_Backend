package com.teamfp.aistock.domain.account.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.teamfp.aistock.domain.account.entity.ChargeRequest;
import com.teamfp.aistock.domain.account.entity.ChargeRequestStatus;

import jakarta.persistence.LockModeType;

public interface ChargeRequestRepository extends JpaRepository<ChargeRequest, Long> {

    // 사용자 본인 조회 — 계좌 소유권은 컨트롤러 이전에 AccountService.getOwnedAccount()가 이미
    // 검증하므로, 여기서는 그 accountId로만 걸러도 안전하다.
    @Query("select c from ChargeRequest c where c.account.accountId = :accountId order by c.requestedAt desc")
    Page<ChargeRequest> findAllByAccountId(@Param("accountId") Long accountId, Pageable pageable);

    @Query("select c from ChargeRequest c where c.requestId = :requestId and c.account.accountId = :accountId")
    Optional<ChargeRequest> findByRequestIdAndAccountId(@Param("requestId") Long requestId, @Param("accountId") Long accountId);

    // 계좌별 미처리 요청 중복 생성 방지(handoff 4.2 "계좌별 미처리 요청 중복 생성 방지 정책이
    // 필요하다" — 정책 미확정 상태에서 "동시에 PENDING 1건만 허용"으로 우선 구현).
    boolean existsByAccount_AccountIdAndStatus(Long accountId, ChargeRequestStatus status);

    // 관리자 충전요청 검색·목록 — query는 계좌 소유자 아이디/이름/계좌번호 통합검색, status는
    // 선택 필터. AccountRepository.searchAccountsWithUser()와 동일한 패턴.
    @Query(value = "select c from ChargeRequest c join fetch c.account a join fetch a.user u "
            + "left join fetch c.decidedBy where "
            + "(:query is null or u.loginId like concat('%', :query, '%') "
            + "or u.name like concat('%', :query, '%') "
            + "or a.accountNumber like concat('%', :query, '%')) "
            + "and (:status is null or c.status = :status)",
            countQuery = "select count(c) from ChargeRequest c join c.account a join a.user u where "
            + "(:query is null or u.loginId like concat('%', :query, '%') "
            + "or u.name like concat('%', :query, '%') "
            + "or a.accountNumber like concat('%', :query, '%')) "
            + "and (:status is null or c.status = :status)")
    Page<ChargeRequest> searchWithAccountAndUser(
            @Param("query") String query, @Param("status") ChargeRequestStatus status, Pageable pageable);

    // 관리자 상세 조회 — account, account.user, decidedBy(처리 관리자, nullable이라 left join)까지 fetch join.
    @Query("select c from ChargeRequest c join fetch c.account a join fetch a.user "
            + "left join fetch c.decidedBy where c.requestId = :requestId")
    Optional<ChargeRequest> findWithAccountAndUserById(@Param("requestId") Long requestId);

    // 승인·거절 처리(decide()) 전용 — 동시 승인/거절 요청이 겹쳐도 한쪽만 반영되도록 비관적
    // 락으로 행을 잠근다. Order/Account의 기존 패턴(findByIdForUpdate 등)과 동일한 이유다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ChargeRequest c where c.requestId = :requestId")
    Optional<ChargeRequest> findByIdForUpdate(@Param("requestId") Long requestId);
}
