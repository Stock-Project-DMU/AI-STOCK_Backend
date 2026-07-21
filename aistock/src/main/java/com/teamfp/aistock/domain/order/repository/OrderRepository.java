package com.teamfp.aistock.domain.order.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.teamfp.aistock.domain.order.entity.Order;
import com.teamfp.aistock.domain.order.entity.OrderStatus;

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
}
