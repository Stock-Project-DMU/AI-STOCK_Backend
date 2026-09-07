package com.teamfp.aistock.domain.account.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.teamfp.aistock.domain.account.entity.Account;
import com.teamfp.aistock.domain.account.entity.AccountStatus;

import jakarta.persistence.LockModeType;

public interface AccountRepository extends JpaRepository<Account, Long> {

    // AccountService.chargeBalance()/findByAccountIdAndUserId(ForUpdate 유무만 다름)가 같은
    // WHERE 절을 쓰므로 상수로 묶어 둘 다 참조한다 — 조건이 바뀔 때 한쪽만 고치고 잊어버리는
    // 실수를 막기 위함(인터페이스 필드는 기본이 public static final이라 @Query에 넣을 수 있다).
    String FIND_BY_ACCOUNT_ID_AND_USER_ID =
            "select a from Account a where a.accountId = :accountId and a.user.userId = :userId";

    // 유저 1명이 계좌를 최대 3개까지 가질 수 있어(feature/mypage-account) 단일 계좌를
    // 가정하던 findByUserId(Long)는 더 이상 쓰지 않는다 — 항상 목록 또는 accountId+userId
    // 조합으로 특정 계좌를 골라 조회한다.
    // ORDER BY 없이는 반환 순서가 보장되지 않아, "첫 번째 계좌"를 가정하는 호출부
    // (예: AiPlanningService.findPrimaryHolding())가 호출마다 다른 계좌를 고를 수 있었다.
    // accountId 오름차순(=생성 순서)으로 고정한다.
    @Query("select a from Account a where a.user.userId = :userId order by a.accountId asc")
    List<Account> findAllByUserId(@Param("userId") Long userId);

    // AccountService.createAccount()용 — 계좌 개수 확인(check)과 신규 계좌 저장(save) 사이에
    // 같은 유저의 동시 계좌 개설 요청을 순서대로 처리하기 위한 비관적 락. 처음에는 User 행을
    // 잠그는 방식(UserRepository.findByIdForUpdate)이었는데, User는 로그인·관리자 정지 등
    // 계좌와 무관한 다른 기능도 앞으로 잠글 수 있는 공용 자원이라 코드 리뷰에서 불필요하게 넓은
    // 락이라는 지적을 받아, Account 쪽만 잠그는 이 메서드로 좁혔다. 매칭되는 행이 0개(첫 계좌
    // 개설)여도 idx_account_user 인덱스 덕분에 InnoDB가 해당 user_id 구간에 갭 락을 걸어
    // 동시 삽입을 막아준다. 반환된 목록의 크기를 그대로 개수 확인에도 재사용해 별도
    // count 쿼리 없이 한 번의 조회로 락과 개수 확인을 같이 처리한다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.user.userId = :userId")
    List<Account> findAllByUserIdForUpdate(@Param("userId") Long userId);

    @Query(FIND_BY_ACCOUNT_ID_AND_USER_ID)
    Optional<Account> findByAccountIdAndUserId(@Param("accountId") Long accountId, @Param("userId") Long userId);

    // AccountService.chargeBalance()용 — chargeCount 확인(check)과 chargeBalance() 반영(act)
    // 사이에 비관적 락으로 같은 계좌에 대한 동시 충전 요청을 순서대로 처리한다. @Version(낙관적
    // 락)만으로는 두 번째 요청이 성공적으로 처리될 수 있었던 상황(예: chargeCount==2일 때 동시
    // 요청 두 건)에서도 그중 하나가 스푸리어스하게 OPTIMISTIC_LOCK_CONFLICT(409)로 실패해버리는
    // 문제가 있어, 그 요청이 먼저 요청을 기다렸다가 갱신된 chargeCount를 다시 보고 정확히
    // 판단하도록 비관적 락으로 바꿨다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(FIND_BY_ACCOUNT_ID_AND_USER_ID)
    Optional<Account> findByAccountIdAndUserIdForUpdate(@Param("accountId") Long accountId, @Param("userId") Long userId);

    Optional<Account> findByAccountNumber(String accountNumber);

    // 관리자 계좌 상세 조회 — account.user까지 fetch join으로 한 번에 즉시 로딩한다
    // (AdminAccountService가 userName을 채워야 하는데, admin은 userId를 모르는 상태로
    // accountId만 갖고 조회하므로 findByAccountIdAndUserId를 쓸 수 없다).
    @Query("select a from Account a join fetch a.user where a.accountId = :accountId")
    Optional<Account> findAccountWithUserById(@Param("accountId") Long accountId);

    // 관리자 잔고 수동 조정(AdminAccountService.adjustBalance()) 전용 — 위 findAccountWithUserById와
    // 달리 비관적 락을 건다(코드리뷰 반영, 2026-09). 락 없이 조회하면 관리자가 잔고를 조정하는
    // 도중 같은 계좌로 주문이 체결(OrderExecutionService.execute())되어 Account.version(낙관적
    // 락) 충돌로 조정 자체가 실패하는 경우가 생길 수 있다 — AccountService.chargeBalance()와
    // 동일한 이유로 비관적 락으로 순서를 맞춘다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a join fetch a.user where a.accountId = :accountId")
    Optional<Account> findAccountWithUserByIdForUpdate(@Param("accountId") Long accountId);

    // 관리자 계좌 목록·검색(feature/admin-api-p0, ADMIN_API_BACKEND_HANDOFF.md 3.4). query는
    // 계좌 소유자 아이디/이름/계좌번호 통합검색이고, status는 선택 필터다. 파라미터가 null이면
    // 해당 조건을 걸지 않는다(컨트롤러가 빈 문자열을 null로 정규화). fetch join을 쓰는 페이징
    // 쿼리라 count 쿼리는 fetch join 없이 별도로 지정한다 — findAllOrdersWithUser와 동일한 이유.
    @Query(value = "select a from Account a join fetch a.user u where "
            + "(:query is null or u.loginId like concat('%', :query, '%') "
            + "or u.name like concat('%', :query, '%') "
            + "or a.accountNumber like concat('%', :query, '%')) "
            + "and (:status is null or a.status = :status)",
            countQuery = "select count(a) from Account a join a.user u where "
            + "(:query is null or u.loginId like concat('%', :query, '%') "
            + "or u.name like concat('%', :query, '%') "
            + "or a.accountNumber like concat('%', :query, '%')) "
            + "and (:status is null or a.status = :status)")
    Page<Account> searchAccountsWithUser(@Param("query") String query, @Param("status") AccountStatus status, Pageable pageable);

    @Modifying
    @Query("delete from Account a where a.user.userId = :userId")
    void deleteByUserId(@Param("userId") Long userId);
}
