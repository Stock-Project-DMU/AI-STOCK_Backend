package com.teamfp.aistock.domain.order.service;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.teamfp.aistock.domain.account.entity.Account;
import com.teamfp.aistock.domain.account.repository.AccountRepository;
import com.teamfp.aistock.domain.order.dto.request.CreateOrderRequest;
import com.teamfp.aistock.domain.order.dto.response.CreateOrderResponse;
import com.teamfp.aistock.domain.order.entity.Holding;
import com.teamfp.aistock.domain.order.entity.OrderType;
import com.teamfp.aistock.domain.order.entity.PriceType;
import com.teamfp.aistock.domain.order.repository.HoldingRepository;
import com.teamfp.aistock.domain.stock.dto.StockPriceDto;
import com.teamfp.aistock.domain.user.entity.Role;
import com.teamfp.aistock.domain.user.entity.User;
import com.teamfp.aistock.domain.user.repository.UserRepository;
import com.teamfp.aistock.global.redis.RedisStockCacheService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * feature/order-market — 실제 스프링 컨텍스트 + 실제 MySQL/Redis(로컬 docker)로 돌리는
 * 눈으로 확인 가능한 통합 테스트.
 *
 * AuthService/AccountService(계좌 생성 API)가 아직 스텁이라 로그인해서 JWT를 받거나
 * HTTP로 계좌를 만들 방법이 없다. 그래서 Controller/JWT 레이어는 우회하고, User/Account를
 * Repository로 직접 저장한 뒤 OrderService 빈을 직접 호출해서 "실제 DB에 실제로 잔고가
 * 차감되고 보유종목이 생기는지"를 검증한다. src/test/resources/application.yml의 테스트 전용
 * 설정으로 로컬 docker MySQL(3307)/Redis(6379)에 붙는다.
 */
@SpringBootTest
class OrderServiceIntegrationTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private HoldingRepository holdingRepository;

    @Autowired
    private RedisStockCacheService redisStockCacheService;

    @Test
    void 실제_DB와_Redis로_현재가_매수주문이_체결된다() {
        // 사용자 눈으로 DB에 남은 실제 행을 직접 확인해볼 수 있도록 이번엔 @Transactional을 빼서
        // 테스트가 끝나도 롤백되지 않고 커밋된 채로 남긴다(재실행 시 중복 방지용으로 매번 다른 값 사용).
        long uniqueSuffix = System.currentTimeMillis();

        // 1) 실제 MySQL에 사용자 + 계좌를 만든다 (잔고 100만원)
        User user = userRepository.save(User.builder()
                .loginId("integration-test-user-" + uniqueSuffix)
                .name("통합테스트유저")
                .role(Role.USER)
                .isActive(true)
                .build());

        Account account = accountRepository.save(Account.builder()
                .user(user)
                .accountNumber("ACC" + (uniqueSuffix % 100_000_000L)) // account_number는 varchar(20)이라 8자리로 축약
                .openedAt(LocalDate.now())
                .baseBalance(1_000_000L)
                .balance(1_000_000L)
                .build());

        System.out.println("========================================");
        System.out.println("[매수 전] userId=" + user.getUserId()
                + ", accountId=" + account.getAccountId()
                + ", balance=" + account.getBalance() + "원");

        // 2) 실제 Redis에 현재가 캐시를 채운다 (LS증권 WebSocket 대신 수동 시딩)
        redisStockCacheService.saveStockPrice("005930", StockPriceDto.builder()
                .stockCode("005930")
                .stockName("삼성전자")
                .currentPrice(70_000L)
                .build());
        System.out.println("[Redis] stock:price:005930 = 70,000원으로 시딩 완료");

        // 3) OrderService 빈을 직접 호출 (Controller/JWT 레이어는 이 테스트 범위 밖)
        CreateOrderRequest request = new CreateOrderRequest("005930", OrderType.BUY, 10, PriceType.MARKET, 0L);
        CreateOrderResponse response = orderService.createMarketOrder(user.getUserId(), request);

        System.out.println("[체결 결과] orderId=" + response.orderId()
                + ", execPrice=" + response.execPrice()
                + ", quantity=" + response.quantity()
                + ", status=" + response.status());

        // 4) DB를 다시 조회해서 실제로 반영됐는지 확인
        Account reloadedAccount = accountRepository.findById(account.getAccountId()).orElseThrow();
        List<Holding> holdings = holdingRepository.findAllByAccountId(account.getAccountId());

        System.out.println("[매수 후] balance=" + reloadedAccount.getBalance() + "원");
        holdings.forEach(h -> System.out.println("[보유종목] " + h.getStockCode() + " "
                + h.getQuantity() + "주, 평단가 " + h.getAvgPrice() + "원"));
        System.out.println("========================================");

        assertThat(reloadedAccount.getBalance()).isEqualTo(300_000L); // 100만원 - (70,000*10)
        assertThat(holdings).hasSize(1);
        assertThat(holdings.get(0).getQuantity()).isEqualTo(10);
        assertThat(holdings.get(0).getAvgPrice()).isEqualTo(70_000L);
    }
}
