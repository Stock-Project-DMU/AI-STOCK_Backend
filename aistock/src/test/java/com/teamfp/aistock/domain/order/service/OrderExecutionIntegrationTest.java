package com.teamfp.aistock.domain.order.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.teamfp.aistock.domain.account.entity.Account;
import com.teamfp.aistock.domain.account.repository.AccountRepository;
import com.teamfp.aistock.domain.notification.repository.NotificationRepository;
import com.teamfp.aistock.domain.order.dto.request.CreateOrderRequest;
import com.teamfp.aistock.domain.order.dto.response.CreateOrderResponse;
import com.teamfp.aistock.domain.order.entity.Holding;
import com.teamfp.aistock.domain.order.entity.Order;
import com.teamfp.aistock.domain.order.entity.OrderStatus;
import com.teamfp.aistock.domain.order.entity.OrderType;
import com.teamfp.aistock.domain.order.entity.PriceType;
import com.teamfp.aistock.domain.order.repository.HoldingRepository;
import com.teamfp.aistock.domain.order.repository.OrderRepository;
import com.teamfp.aistock.domain.stock.dto.StockPriceDto;
import com.teamfp.aistock.domain.stock.service.StockBroadcastService;
import com.teamfp.aistock.domain.user.entity.Role;
import com.teamfp.aistock.domain.user.entity.User;
import com.teamfp.aistock.domain.user.repository.UserRepository;
import com.teamfp.aistock.global.redis.RedisStockCacheService;
import com.teamfp.aistock.infra.marketdata.dto.TickData;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 갭 리포트 ⑫ — 지정가 실시간 체결 검증(실제 스프링 컨텍스트 + 실제 MySQL/Redis 통합 테스트).
 *
 * StockBroadcastServiceTest는 OrderExecutionService를 Mockito로 대체해 "호출됐는지"만 확인하므로,
 * tick 수신 시 지정가 주문이 DB에 실제로 체결(잔고/보유종목/알림 반영)되는지는 여전히 검증하지
 * 못한다. OrderServiceIntegrationTest와 같은 방식(Controller/JWT 우회, LS WebSocket 대신
 * StockBroadcastService.onTickReceived()를 직접 호출해 tick을 수동 주입)으로 그 빈틈을 메운다.
 *
 * 실행 전 docker compose(MySQL 3306 또는 DB_PORT로 오버라이드한 포트, Redis 6379)가 떠 있어야 한다.
 */
@SpringBootTest
class OrderExecutionIntegrationTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private StockBroadcastService stockBroadcastService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private HoldingRepository holdingRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private RedisStockCacheService redisStockCacheService;

    @Test
    void 지정가_매수_주문이_tick_수신으로_실제_체결된다() throws InterruptedException {
        long uniqueSuffix = System.currentTimeMillis();
        String stockCode = "005930";

        // 1) 실제 MySQL에 사용자 + 계좌를 만든다 (잔고 100만원)
        User user = userRepository.save(User.builder()
                .loginId("order-exec-test-user-" + uniqueSuffix)
                .name("체결테스트유저")
                .role(Role.USER)
                .isActive(true)
                .build());

        Account account = accountRepository.save(Account.builder()
                .user(user)
                .accountName("체결테스트계좌")
                .accountNumber("OE" + (uniqueSuffix % 10_000_000L))
                .openedAt(LocalDate.now())
                .baseBalance(1_000_000L)
                .balance(1_000_000L)
                .build());

        // 2) 신규 종목 지정가 매수를 위해 stockName 조회용 현재가 캐시를 시딩한다
        //    (createLimitOrder는 미보유 종목이면 stock:price 캐시에서 종목명을 가져온다)
        redisStockCacheService.saveStockPrice(stockCode, StockPriceDto.builder()
                .stockCode(stockCode)
                .stockName("삼성전자")
                .currentPrice(70_000L)
                .build());

        System.out.println("========================================");
        System.out.println("[주문 전] userId=" + user.getUserId()
                + ", accountId=" + account.getAccountId()
                + ", balance=" + account.getBalance() + "원");

        // 3) 지정가 매수 10주 @ 70,000원 등록 (700,000원 frozenBalance로 묶임)
        CreateOrderRequest request = new CreateOrderRequest(account.getAccountId(), stockCode, OrderType.BUY, 10, PriceType.LIMIT, 70_000L);
        CreateOrderResponse response = orderService.createLimitOrder(user.getUserId(), request);
        System.out.println("[지정가 등록] orderId=" + response.orderId() + ", status=" + response.status());

        Account afterRegister = accountRepository.findById(account.getAccountId()).orElseThrow();
        assertThat(afterRegister.getBalance()).isEqualTo(300_000L); // 1,000,000 - 700,000
        assertThat(afterRegister.getFrozenBalance()).isEqualTo(700_000L);

        // registerAfterCommit(트랜잭션 커밋 후 Redis pending:orders 등록)이 별도 스레드/콜백이라
        // Redis 반영이 이 시점 이후 아주 약간 늦어질 수 있어 짧게 대기한다.
        Thread.sleep(300);

        // 4) 외부 시세 데이터 WebSocket 대신 StockBroadcastService.onTickReceived()로 tick을 직접 주입한다.
        //    지정가(70,000) 이하인 65,000원 체결 tick → 매수 조건(현재가 <= 지정가) 충족.
        TickData tickData = TickData.builder()
                .stockCode(stockCode)
                .stockName("삼성전자")
                .currentPrice(65_000L)
                .changeRate(-1.0)
                .changeAmount(1000)
                .volume(1000)
                .tradedAt(LocalDateTime.now())
                .build();
        stockBroadcastService.onTickReceived(tickData);

        // OrderExecutionService.execute()는 별도 트랜잭션(self-invocation 프록시)으로 커밋되므로
        // onTickReceived() 리턴 직후 짧게 대기 후 재조회한다.
        Thread.sleep(300);

        // 5) 실제 DB를 다시 조회해서 체결 결과를 검증한다
        Order reloadedOrder = orderRepository.findById(response.orderId()).orElseThrow();
        Account reloadedAccount = accountRepository.findById(account.getAccountId()).orElseThrow();
        List<Holding> holdings = holdingRepository.findAllByAccountId(account.getAccountId());

        System.out.println("[체결 후] orderStatus=" + reloadedOrder.getStatus()
                + ", execPrice=" + reloadedOrder.getExecPrice()
                + ", balance=" + reloadedAccount.getBalance()
                + ", frozenBalance=" + reloadedAccount.getFrozenBalance());
        holdings.forEach(h -> System.out.println("[보유종목] " + h.getStockCode() + " "
                + h.getQuantity() + "주, 평단가 " + h.getAvgPrice() + "원"));
        System.out.println("========================================");

        assertThat(reloadedOrder.getStatus()).isEqualTo(OrderStatus.EXECUTED);
        assertThat(reloadedOrder.getExecPrice()).isEqualTo(65_000L);
        assertThat(reloadedAccount.getFrozenBalance()).isEqualTo(0L);
        assertThat(reloadedAccount.getBalance()).isEqualTo(350_000L); // 300,000 + (700,000 - 650,000) 환급
        assertThat(holdings).hasSize(1);
        assertThat(holdings.get(0).getQuantity()).isEqualTo(10);
        assertThat(holdings.get(0).getAvgPrice()).isEqualTo(65_000L);

        // 6) 체결 알림이 실제로 저장됐는지 확인
        boolean hasExecutionNotification = notificationRepository.findAllByUserIdOrderByCreatedAtDesc(user.getUserId())
                .stream()
                .anyMatch(n -> n.getContent().contains("체결되었습니다"));
        assertThat(hasExecutionNotification).isTrue();
    }
}
