package com.teamfp.aistock.domain.order.repository;

import java.util.List;
import java.util.Optional;

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
}
