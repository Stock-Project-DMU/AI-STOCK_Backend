package com.teamfp.aistock.domain.account.repository;

import java.util.List;
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

    // ChargeRequestService.getChargeHistory()용 — 계좌의 충전 요청 전체(대기·승인·거절).
    List<ChargeRequest> findAllByAccount_AccountId(Long accountId);

    @Query("select c from ChargeRequest c where c.requestId = :requestId and c.account.accountId = :accountId")
    Optional<ChargeRequest> findByRequestIdAndAccountId(@Param("requestId") Long requestId, @Param("accountId") Long accountId);

    // 계좌별 미처리 요청 중복 생성 방지(handoff 4.2 "계좌별 미처리 요청 중복 생성 방지 정책이
    // 필요하다" — 정책 미확정 상태에서 "동시에 PENDING 1건만 허용"으로 우선 구현).
    boolean existsByAccount_AccountIdAndStatus(Long accountId, ChargeRequestStatus status);

    // 관리자 충전요청 검색·목록 — query는 요청번호/계좌 소유자 아이디/이름/계좌번호 검색, status는
    // 선택 필터. AccountRepository.searchAccountsWithUser()와 동일한 패턴.
    // feat/admin-improvements: 검색 항목(field)과 검색 방식(exact — 정확히 일치/포함)을 고를 수 있게 바꿨다.
    // 파라미터는 AdminSearchConditionDto가 만든다 — ID 항목은 queryId와 정확히 일치할 때만, 문자열 항목은
    // exact면 =, 아니면 LIKE(:pattern, %·_는 '!'로 이스케이프)로 찾는다.
    @Query(value = "select c from ChargeRequest c join fetch c.account a join fetch a.user u left join fetch c.decidedBy where (:query is null or (((:field = 'ALL' or :field = 'REQUEST_ID') "
            + "and c.requestId = :queryId) or ((:field = 'ALL' or :field = 'LOGIN_ID') "
            + "and ((:exact = true and u.loginId = :query) "
            + "or (:exact = false and u.loginId like :pattern escape '!'))) or ((:field = 'ALL' or :field = 'NAME') "
            + "and ((:exact = true and u.name = :query) or (:exact = false and u.name like :pattern escape '!'))) "
            + "or ((:field = 'ALL' or :field = 'ACCOUNT_NUMBER') and ((:exact = true and a.accountNumber = :query) "
            + "or (:exact = false and a.accountNumber like :pattern escape '!'))))) "
            + "and (:status is null or c.status = :status)",
            countQuery = "select count(c) from ChargeRequest c join c.account a join a.user u where (:query is null or (((:field = 'ALL' or :field = 'REQUEST_ID') "
            + "and c.requestId = :queryId) or ((:field = 'ALL' or :field = 'LOGIN_ID') "
            + "and ((:exact = true and u.loginId = :query) "
            + "or (:exact = false and u.loginId like :pattern escape '!'))) or ((:field = 'ALL' or :field = 'NAME') "
            + "and ((:exact = true and u.name = :query) or (:exact = false and u.name like :pattern escape '!'))) "
            + "or ((:field = 'ALL' or :field = 'ACCOUNT_NUMBER') and ((:exact = true and a.accountNumber = :query) "
            + "or (:exact = false and a.accountNumber like :pattern escape '!'))))) "
            + "and (:status is null or c.status = :status)")
    Page<ChargeRequest> searchWithAccountAndUser(
            @Param("query") String query, @Param("pattern") String pattern, @Param("queryId") Long queryId,
            @Param("field") String field, @Param("exact") boolean exact,
            @Param("status") ChargeRequestStatus status, Pageable pageable);

    // 관리자 상세 조회 — account, account.user, decidedBy(처리 관리자, nullable이라 left join)까지 fetch join.
    @Query("select c from ChargeRequest c join fetch c.account a join fetch a.user "
            + "left join fetch c.decidedBy where c.requestId = :requestId")
    Optional<ChargeRequest> findWithAccountAndUserById(@Param("requestId") Long requestId);

    // 관리자 "전체 활동 기록"(AdminActivityService) — 한 페이지에 나온 충전 요청들을 회원 정보까지 한 번에 조회
    @Query("select c from ChargeRequest c join fetch c.account a join fetch a.user left join fetch c.decidedBy "
            + "where c.requestId in :requestIds")
    List<ChargeRequest> findAllWithUserByRequestIdIn(@Param("requestIds") List<Long> requestIds);

    // 승인·거절 처리(decide()) 전용 — 동시 승인/거절 요청이 겹쳐도 한쪽만 반영되도록 비관적
    // 락으로 행을 잠근다. Order/Account의 기존 패턴(findByIdForUpdate 등)과 동일한 이유다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ChargeRequest c where c.requestId = :requestId")
    Optional<ChargeRequest> findByIdForUpdate(@Param("requestId") Long requestId);
}
