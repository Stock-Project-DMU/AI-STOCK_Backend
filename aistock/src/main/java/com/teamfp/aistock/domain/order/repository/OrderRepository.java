package com.teamfp.aistock.domain.order.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.teamfp.aistock.domain.order.entity.Order;
import com.teamfp.aistock.domain.order.entity.OrderStatus;

import jakarta.persistence.LockModeType;

public interface OrderRepository extends JpaRepository<Order, Long> {

    List<Order> findAllByStockCodeAndStatus(String stockCode, OrderStatus status);

    @Query("select o from Order o where o.account.accountId = :accountId order by o.orderedAt desc")
    List<Order> findAllByAccountIdOrderByOrderedAtDesc(@Param("accountId") Long accountId);

    @Query("select o from Order o where o.orderId = :orderId and o.account.accountId = :accountId")
    Optional<Order> findByOrderIdAndAccountId(@Param("orderId") Long orderId, @Param("accountId") Long accountId);

    // account, account.user까지 fetch join으로 한 번에 즉시 로딩한다.
    // 세션이 끝난 뒤(@PostConstruct 등)에 LAZY 프록시를 건드려 LazyInitializationException이
    // 나는 것을 막기 위한 전용 조회 메서드.
    @Query("select o from Order o join fetch o.account a join fetch a.user where o.status = :status")
    List<Order> findAllByStatusWithAccountAndUser(@Param("status") OrderStatus status);

    // 관리자 대시보드 — 총 거래건수
    long countByStatus(OrderStatus status);

    // 관리자 대시보드 — 최근 거래 20건
    List<Order> findTop20ByStatusOrderByExecutedAtDesc(OrderStatus status);

    // 관리자 전체 거래 목록 — account, account.user까지 fetch join + 페이징
    @Query(value = "select o from Order o join fetch o.account a join fetch a.user",
            countQuery = "select count(o) from Order o")
    Page<Order> findAllOrdersWithUser(Pageable pageable);

    // 관리자 거래 상세 — account, account.user까지 fetch join
    @Query("select o from Order o join fetch o.account a join fetch a.user where o.orderId = :orderId")
    Optional<Order> findOrderWithUserById(@Param("orderId") Long orderId);

    // 관리자 대시보드 — 체결 완료된 주문의 총 거래대금(체결가 * 수량 합계)
    @Query("select coalesce(sum(o.execPrice * o.quantity), 0) from Order o where o.status = 'EXECUTED'")
    long sumExecutedAmount();

    // v9부터 Order에도 @Version(낙관적 락)이 있지만, 그것만으로는 같은 주문을 동시에
    // 체결(OrderExecutionService.execute)/취소(OrderService.cancelOrder)하려는 두 트랜잭션이
    // "먼저 커밋되는 쪽이 이긴다"는 보장만 해줄 뿐 순서를 제어하진 못한다 — 매도 취소처럼
    // Account를 건드리지 않는 경로는 낙관적 락 실패 시 처음부터 재시도해야 하는데, 지정가
    // 체결은 tick마다 자동 재시도되지만 사용자의 취소 요청은 그 자리에서 바로 실패 응답을
    // 주는 게 UX상 맞다. 그래서 이 두 메서드는 비관적 락(SELECT ... FOR UPDATE)으로 Order
    // 행 자체를 직접 잠가 두 트랜잭션이 겹치지 않고 순서대로 처리되게 한다(HoldingRepository와
    // 동일한 패턴). @Version은 이 두 메서드를 거치지 않는 다른 조회 경로(관리자 기능 등)로
    // 실수로 Order를 수정하더라도 최소한의 동시성 보호를 받게 하는 안전망 역할이다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.orderId = :orderId")
    Optional<Order> findByIdForUpdate(@Param("orderId") Long orderId);

    // feature/mypage-account 추가 — OrderService.cancelOrder()용. 유저가 계좌를 여러 개 가질 수
    // 있게 되면서 한때 findByIdForUpdate(orderId)로 먼저 잠근 뒤 order.getAccount().getUser()로
    // 소유권을 나중에 검증하는 방식을 썼는데, 그러면 다른 유저의 orderId를 넣어도 검증에 앞서
    // 비관적 락부터 잡혀버려(OrderExecutionService.execute()가 같은 락을 기다려야 하는 락 경합,
    // 그리고 "존재하지만 내 것이 아님"과 "존재하지 않음"을 응답 시간 차이로 구분당할 수 있는
    // 타이밍 사이드채널) 문제였다. 소유권(user_id)은 주문 생성 이후 절대 바뀌지 않는 값이라
    // WHERE 절에 넣어도 안전하므로, 과거 findByOrderIdAndAccountIdForUpdate와 동일한 방식으로
    // 락 자체를 소유자 본인 소유의 행에만 걸리도록 되돌린다(대상만 accountId 대신 userId로 조인).
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.orderId = :orderId and o.account.user.userId = :userId")
    Optional<Order> findByOrderIdAndUserIdForUpdate(@Param("orderId") Long orderId, @Param("userId") Long userId);

    // 같은 계좌·종목으로 이미 등록된 PENDING 지정가 매도 주문의 수량 합계.
    // createLimitOrder()가 매도 등록 시점에 "보유수량 - 이미 대기 중인 매도 수량"으로 검증해,
    // 같은 종목을 보유수량보다 많이 중복으로 매도 등록하는 것을 막는 데 쓴다.
    @Query("select coalesce(sum(o.quantity), 0) from Order o "
            + "where o.account.accountId = :accountId and o.stockCode = :stockCode "
            + "and o.orderType = 'SELL' and o.status = 'PENDING'")
    int sumPendingSellQuantity(@Param("accountId") Long accountId, @Param("stockCode") String stockCode);
}
