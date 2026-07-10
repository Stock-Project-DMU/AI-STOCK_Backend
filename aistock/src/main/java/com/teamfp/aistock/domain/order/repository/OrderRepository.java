package com.teamfp.aistock.domain.order.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.teamfp.aistock.domain.order.entity.Order;
import com.teamfp.aistock.domain.order.entity.OrderStatus;

public interface OrderRepository extends JpaRepository<Order, Long> {

    List<Order> findAllByStatus(OrderStatus status);

    List<Order> findAllByStockCodeAndStatus(String stockCode, OrderStatus status);

    @Query("select o from Order o where o.account.accountId = :accountId order by o.orderedAt desc")
    List<Order> findAllByAccountIdOrderByOrderedAtDesc(@Param("accountId") Long accountId);

    @Query("select o from Order o where o.orderId = :orderId and o.account.accountId = :accountId")
    Optional<Order> findByOrderIdAndAccountId(@Param("orderId") Long orderId, @Param("accountId") Long accountId);
}
