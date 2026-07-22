package com.teamfp.aistock.domain.order.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import com.teamfp.aistock.domain.account.entity.Account;
import com.teamfp.aistock.domain.account.repository.AccountRepository;
import com.teamfp.aistock.domain.order.entity.Order;
import com.teamfp.aistock.domain.order.entity.OrderStatus;
import com.teamfp.aistock.domain.order.entity.OrderType;
import com.teamfp.aistock.domain.order.entity.PriceType;
import com.teamfp.aistock.domain.user.entity.Role;
import com.teamfp.aistock.domain.user.entity.User;
import com.teamfp.aistock.domain.user.repository.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * chore/admin-entity-update — OrderRepository 관리자 대시보드/거래관리 집계 메서드
 * 실제 MySQL 연동 테스트. 사전 조건: 로컬 docker-compose(MySQL 3306)가 떠 있어야 한다.
 * @Transactional로 감싸서 테스트 종료 후 자동 롤백된다.
 */
@SpringBootTest
@Transactional
class OrderRepositoryIntegrationTest {

    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private UserRepository userRepository;

    private Account newAccount(String suffix) {
        User user = userRepository.save(User.builder()
                .loginId("order-repo-test-" + suffix)
                .name("주문레포테스터")
                .role(Role.USER)
                .isActive(true)
                .build());
        return accountRepository.save(Account.builder()
                .user(user)
                .accountName("테스트계좌")
                .accountNumber("O" + (Math.abs(suffix.hashCode()) % 10_000_000))
                .openedAt(LocalDate.now())
                .baseBalance(1_000_000L)
                .balance(1_000_000L)
                .build());
    }

    private Order newExecutedOrder(Account account, long execPrice, int quantity) {
        Order order = Order.builder()
                .account(account)
                .stockCode("005930")
                .stockName("삼성전자")
                .orderType(OrderType.BUY)
                .priceType(PriceType.MARKET)
                .orderPrice(execPrice)
                .quantity(quantity)
                .build();
        order.execute(execPrice);
        return orderRepository.save(order);
    }

    private Order newPendingLimitOrder(Account account) {
        Order order = Order.builder()
                .account(account)
                .stockCode("005930")
                .stockName("삼성전자")
                .orderType(OrderType.BUY)
                .priceType(PriceType.LIMIT)
                .orderPrice(70_000L)
                .quantity(1)
                .build();
        return orderRepository.save(order);
    }

    @Test
    @DisplayName("countByStatus(EXECUTED)는 체결된 주문 수만 센다")
    void countByStatus_countsOnlyExecutedOrders() {
        Account account = newAccount(String.valueOf(System.nanoTime()));
        long before = orderRepository.countByStatus(OrderStatus.EXECUTED);

        newExecutedOrder(account, 70_000L, 1);
        newExecutedOrder(account, 70_000L, 2);

        assertThat(orderRepository.countByStatus(OrderStatus.EXECUTED) - before).isEqualTo(2);
    }

    @Test
    @DisplayName("findTop20ByStatusOrderByExecutedAtDesc는 체결된 주문을 최근 체결순으로 반환한다")
    void findTop20ByStatusOrderByExecutedAtDesc_returnsExecutedOrders() {
        Account account = newAccount(String.valueOf(System.nanoTime()));
        Order order = newExecutedOrder(account, 70_000L, 3);

        List<Order> recentTrades = orderRepository.findTop20ByStatusOrderByExecutedAtDesc(OrderStatus.EXECUTED);

        assertThat(recentTrades).extracting(Order::getOrderId).contains(order.getOrderId());
        assertThat(recentTrades).allMatch(o -> o.getStatus() == OrderStatus.EXECUTED);
    }

    @Test
    @DisplayName("findAllOrdersWithUser는 account.user까지 fetch join된 상태로 페이징 조회한다")
    void findAllOrdersWithUser_fetchJoinsAccountAndUser() {
        Account account = newAccount(String.valueOf(System.nanoTime()));
        Order order = newExecutedOrder(account, 70_000L, 1);

        Page<Order> page = orderRepository.findAllOrdersWithUser(PageRequest.of(0, 100));

        Order found = page.getContent().stream()
                .filter(o -> o.getOrderId().equals(order.getOrderId()))
                .findFirst()
                .orElseThrow();
        assertThat(found.getAccount().getUser().getLoginId()).isNotNull();
    }

    @Test
    @DisplayName("findOrderWithUserById는 특정 주문을 account.user까지 fetch join해서 조회한다")
    void findOrderWithUserById_fetchJoinsAccountAndUser() {
        Account account = newAccount(String.valueOf(System.nanoTime()));
        Order order = newExecutedOrder(account, 70_000L, 1);

        Optional<Order> found = orderRepository.findOrderWithUserById(order.getOrderId());

        assertThat(found).isPresent();
        assertThat(found.get().getAccount().getUser().getLoginId()).isEqualTo(account.getUser().getLoginId());
    }

    @Test
    @DisplayName("sumExecutedAmount는 체결가*수량의 합계를 반환한다")
    void sumExecutedAmount_returnsSumOfExecutedOrders() {
        Account account = newAccount(String.valueOf(System.nanoTime()));
        long before = orderRepository.sumExecutedAmount();

        newExecutedOrder(account, 70_000L, 10); // 700,000
        newExecutedOrder(account, 50_000L, 4);  // 200,000

        assertThat(orderRepository.sumExecutedAmount() - before).isEqualTo(900_000L);
    }

    @Test
    @DisplayName("findByOrderIdAndUserIdForUpdate는 주문 소유자 본인의 userId로만 조회되고, 다른 유저의 userId로는 조회되지 않는다")
    void findByOrderIdAndUserIdForUpdate_onlyMatchesOwner() {
        // OrderService.cancelOrder()가 이 메서드로 소유권 검증과 비관적 락을 동시에 처리하므로
        // (코드 리뷰에서 지적된 부분 — 유닛 테스트는 Mockito로 리포지토리를 모킹해서 실제 JPQL의
        // WHERE 절(o.account.user.userId = :userId)이 남의 주문을 정말로 걸러내는지 검증하지
        // 못한다), 실제 MySQL로 그 WHERE 절 자체를 검증한다.
        Account ownerAccount = newAccount(String.valueOf(System.nanoTime()));
        Account otherAccount = newAccount(String.valueOf(System.nanoTime() + 1));
        Order order = newPendingLimitOrder(ownerAccount);

        Optional<Order> foundByOwner = orderRepository.findByOrderIdAndUserIdForUpdate(
                order.getOrderId(), ownerAccount.getUser().getUserId());
        Optional<Order> foundByOtherUser = orderRepository.findByOrderIdAndUserIdForUpdate(
                order.getOrderId(), otherAccount.getUser().getUserId());

        assertThat(foundByOwner).isPresent();
        assertThat(foundByOtherUser).isEmpty();
    }
}
