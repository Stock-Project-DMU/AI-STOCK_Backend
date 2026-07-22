package com.teamfp.aistock.domain.order.service;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.teamfp.aistock.domain.account.entity.Account;
import com.teamfp.aistock.domain.account.entity.AccountStatus;
import com.teamfp.aistock.domain.account.repository.AccountRepository;
import com.teamfp.aistock.domain.order.dto.PendingOrderDto;
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
import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;
import com.teamfp.aistock.global.redis.RedisPendingOrderService;
import com.teamfp.aistock.global.redis.RedisStockCacheService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final HoldingRepository holdingRepository;
    private final AccountRepository accountRepository;
    private final HoldingSettlementService holdingSettlementService;
    // 현재가는 도메인 간 서비스 호출(domain.stock.StockService)이 아니라
    // global/redis 서비스를 직접 조회한다 — feature/stock-price가 아직 구현 전이라
    // StockService에 의존하면 이 기능이 그쪽 완료를 기다려야 하고, CLAUDE.md 7번
    // 규칙상 Redis 접근은 어차피 global/redis 서비스를 통해서만 하도록 되어 있어
    // 직접 주입이 규칙에도 어긋나지 않는다.
    private final RedisStockCacheService redisStockCacheService;
    // 지정가 미체결 주문 대기 목록(pending:orders:{stockCode}) 관리도 같은 이유로
    // global/redis 서비스를 직접 주입받아 쓴다.
    private final RedisPendingOrderService redisPendingOrderService;

    /**
     * 현재가(시장가) 주문 — 잔고/보유수량을 확인한 뒤 실시간 현재가로 즉시 체결한다.
     * 지정가 주문(feature/order-limit)과 달리 pending:orders에 쌓아두지 않고
     * 이 메서드 안에서 매수/매도를 바로 완결한다.
     *
     * 이 메서드는 request.priceType()이 항상 MARKET이라고 전제한다 — LIMIT 요청을 막는 검증은
     * priceType으로 시장가/지정가를 분기하는 책임을 가진 OrderController에서 이미 처리한다.
     *
     * NAMING.md 8-5 v8 항목: 진입 시 account.getStatus()가 SUSPENDED(관리자에 의한 계좌 거래
     * 정지)면 CustomException(ErrorCode.ACCOUNT_SUSPENDED)를 던진다.
     */
    @Transactional
    public CreateOrderResponse createMarketOrder(Long userId, CreateOrderRequest request) {
        Account account = accountRepository.findByUserId(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.ACCOUNT_NOT_FOUND));
        if (account.getStatus() == AccountStatus.SUSPENDED) {
            throw new CustomException(ErrorCode.ACCOUNT_SUSPENDED);
        }

        // 캐시(TTL 5초)에 최근 tick이 없으면 null — 종목 자체가 없는 게 아니라 시세를 일시적으로
        // 못 가져오는 상황이므로 STOCK_NOT_FOUND(종목 없음)와 구분되는 전용 코드를 쓴다.
        StockPriceDto priceDto = redisStockCacheService.getStockPrice(request.stockCode());
        if (priceDto == null) {
            throw new CustomException(ErrorCode.STOCK_PRICE_NOT_AVAILABLE);
        }

        long currentPrice = priceDto.getCurrentPrice();
        long totalAmount = currentPrice * request.quantity();

        if (request.orderType() == OrderType.BUY) {
            executeBuy(account, request, priceDto.getStockName(), currentPrice, totalAmount);
        } else {
            executeSell(account, request, totalAmount);
        }

        // 현재가 주문은 요청의 orderPrice를 쓰지 않고 항상 방금 조회한 currentPrice로
        // 주문가/체결가를 채운다 — 시장가 주문 특성상 "주문가 = 체결가"가 곧 현재가다.
        Order order = Order.builder()
                .account(account)
                .stockCode(request.stockCode())
                .stockName(priceDto.getStockName())
                .orderType(request.orderType())
                .priceType(request.priceType())
                .orderPrice(currentPrice)
                .quantity(request.quantity())
                .build();
        order.execute(currentPrice);
        orderRepository.save(order);

        return CreateOrderResponse.from(order);
    }

    private void executeBuy(Account account, CreateOrderRequest request, String stockName, long currentPrice, long totalAmount) {
        if (account.getBalance() < totalAmount) {
            throw new CustomException(ErrorCode.INSUFFICIENT_BALANCE);
        }
        account.applyBuyOrder(totalAmount);
        holdingSettlementService.increaseOrCreate(account, request.stockCode(), stockName, request.quantity(), currentPrice);
    }

    private void executeSell(Account account, CreateOrderRequest request, long totalAmount) {
        Holding holding = findHolding(account, request.stockCode())
                .orElseThrow(() -> new CustomException(ErrorCode.INSUFFICIENT_HOLDING));
        if (holding.getQuantity() < request.quantity()) {
            throw new CustomException(ErrorCode.INSUFFICIENT_HOLDING);
        }

        account.applySellOrder(totalAmount);
        holdingSettlementService.decrease(holding, request.quantity());
    }

    private Optional<Holding> findHolding(Account account, String stockCode) {
        return holdingRepository.findByAccountIdAndStockCode(account.getAccountId(), stockCode);
    }

    /**
     * 지정가 주문 — 매수는 주문금액(지정가 × 수량)을 balance에서 frozenBalance로 묶어두고,
     * 매도는 보유수량만 확인한 뒤 DB에 PENDING으로 등록하고 Redis pending:orders에 함께 올린다.
     * 실제 체결은 여기서 하지 않는다 — LS증권 tick 수신 시 OrderExecutionService가 처리한다.
     *
     * 이 메서드는 request.priceType()이 항상 LIMIT라고 전제한다 — MARKET/LIMIT 분기 책임은
     * createMarketOrder()와 마찬가지로 OrderController에 있다.
     *
     * createMarketOrder()와 동일하게, 진입 시 account.getStatus()가 SUSPENDED면
     * CustomException(ErrorCode.ACCOUNT_SUSPENDED)를 던진다.
     *
     * 격리수준을 READ_COMMITTED로 지정하는 이유: MySQL 기본(REPEATABLE READ)에서는 한
     * 트랜잭션의 일반 조회(FOR UPDATE가 아닌 SELECT)가 그 트랜잭션의 "첫 조회 시점" 스냅샷을
     * 계속 사용한다. 이 메서드 맨 위 accountRepository.findByUserId()가 그 첫 조회라 스냅샷이
     * 거기서 고정되는데, 매도 분기의 findHolding()(FOR UPDATE)이 다른 트랜잭션의 커밋을
     * 기다렸다가 락을 얻어도 — FOR UPDATE는 최신 커밋 데이터를 보지만 스냅샷 자체를 갱신하진
     * 않는다 — 바로 다음의 sumPendingSellQuantity()(일반 조회)는 여전히 그 오래된 스냅샷을 볼
     * 수 있어서, 방금 다른 트랜잭션이 커밋한 매도 주문을 못 보고 넘어갈 수 있다. 이는 sell 예약
     * 검증(바로 아래 SELL 분기)이 막으려는 것과 같은 종류의 이중예약 경합이라, 이 메서드
     * 안에서만큼은 모든 조회가 매번 최신 커밋 데이터를 보도록 READ_COMMITTED로 지정한다.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CreateOrderResponse createLimitOrder(Long userId, CreateOrderRequest request) {
        Account account = accountRepository.findByUserId(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.ACCOUNT_NOT_FOUND));
        if (account.getStatus() == AccountStatus.SUSPENDED) {
            throw new CustomException(ErrorCode.ACCOUNT_SUSPENDED);
        }

        // MARKET 주문에서는 0을 허용하지만(@PositiveOrZero), 지정가 주문에서 0원은 의미가 없으므로
        // 여기서 별도로 막는다. priceType에 따라 허용 범위가 달라져 DTO의 필드 단위 애노테이션만으로는
        // 표현할 수 없는 검증이라 서비스 레이어에서 처리한다.
        if (request.orderPrice() <= 0) {
            throw new CustomException(ErrorCode.INVALID_INPUT);
        }

        long totalAmount = request.orderPrice() * request.quantity();
        String stockName;

        if (request.orderType() == OrderType.BUY) {
            if (account.getBalance() < totalAmount) {
                throw new CustomException(ErrorCode.INSUFFICIENT_BALANCE);
            }
            // 이미 보유 중인 종목이면 holdings에 저장된 이름을 그대로 쓴다 — SELL 분기와 동일한
            // 이유로, 이미 보유 중인 종목의 추가 매수는 stock:price 캐시가 비어 있어도(TTL 만료 등)
            // 막을 이유가 없다. 이 프로젝트는 종목 마스터 테이블을 따로 두지 않으므로(schema.sql
            // 기준 13개 테이블에 없음), 아직 한 번도 보유한 적 없는 종목을 신규로 지정가 매수할 때만
            // stockName을 얻을 수 있는 유일한 소스인 LS증권 tick 캐시(stock:price)를 조회한다.
            Optional<Holding> existingHolding = findHolding(account, request.stockCode());
            if (existingHolding.isPresent()) {
                stockName = existingHolding.get().getStockName();
            } else {
                StockPriceDto priceDto = redisStockCacheService.getStockPrice(request.stockCode());
                if (priceDto == null) {
                    throw new CustomException(ErrorCode.STOCK_PRICE_NOT_AVAILABLE);
                }
                stockName = priceDto.getStockName();
            }
            account.freezeForOrder(totalAmount);
        } else {
            Holding holding = findHolding(account, request.stockCode())
                    .orElseThrow(() -> new CustomException(ErrorCode.INSUFFICIENT_HOLDING));
            // 매수의 frozenBalance와 대칭되는 예약 검증 — 이미 같은 종목으로 등록해둔 PENDING
            // 매도 주문 수량을 보유수량에서 미리 빼서 확인한다. 이렇게 하지 않으면 10주를 보유한
            // 계좌가 "10주 매도" 주문을 두 건 걸었을 때 둘 다 등록 시점 검증은 통과해버리고,
            // 실제로는 나중에(OrderExecutionService.executeSell) 체결 시점에서야 수량 부족으로
            // 조용히 취소돼 사용자가 이유를 알기 어렵다. 등록 시점에 미리 막아 그 경우를 줄인다.
            int alreadyPendingSellQuantity = orderRepository.sumPendingSellQuantity(account.getAccountId(), request.stockCode());
            if (holding.getQuantity() - alreadyPendingSellQuantity < request.quantity()) {
                throw new CustomException(ErrorCode.INSUFFICIENT_HOLDING);
            }
            // 매도는 이미 보유 중인 종목이라 holdings에 저장된 이름을 그대로 쓰면 되고,
            // frozen_balance 같은 별도 예약 잠금도 필요 없다(주식 자체를 이미 갖고 있으므로).
            stockName = holding.getStockName();
        }

        Order order = Order.builder()
                .account(account)
                .stockCode(request.stockCode())
                .stockName(stockName)
                .orderType(request.orderType())
                .priceType(PriceType.LIMIT)
                .orderPrice(request.orderPrice())
                .quantity(request.quantity())
                .build();
        orderRepository.save(order);

        PendingOrderDto pendingOrderDto = PendingOrderDto.builder()
                .orderId(order.getOrderId())
                .userId(userId)
                .accountId(account.getAccountId())
                .orderType(request.orderType().name())
                .limitPrice(request.orderPrice())
                .quantity(request.quantity())
                .stockCode(request.stockCode())
                .stockName(stockName)
                .build();
        // 이 트랜잭션이 실제로 커밋된 뒤에만 Redis에 반영한다. 여기서 바로 addPendingOrder를
        // 부르면, 이후 이 메서드가 리턴되기 전 커밋 시점에 Account.version 낙관적 락 충돌 등으로
        // 트랜잭션 전체가 롤백되더라도 Redis 호출은 롤백 대상이 아니라서 DB에는 없는 주문이
        // Redis pending:orders에만 유령처럼 남는 문제를 막기 위함이다.
        registerAfterCommit(() -> redisPendingOrderService.addPendingOrder(request.stockCode(), pendingOrderDto));

        return CreateOrderResponse.from(order);
    }

    /**
     * 미체결(PENDING) 지정가 주문을 취소한다. 매수 주문이었다면 freezeForOrder로 묶어둔
     * frozenBalance를 balance로 되돌리고, DB 상태를 CANCELLED로 바꾼 뒤 Redis
     * pending:orders 목록에서도 제거한다.
     *
     * findByOrderIdAndAccountIdForUpdate로 조회해 소유권(내 계좌의 주문이 맞는지) 검증과 동시에
     * 이 Order 행에 비관적 락(SELECT ... FOR UPDATE)을 건다 — Order.version(v9 추가)이라는
     * 최소한의 안전망은 있지만, 매도 주문은 취소 시 Account를 전혀 건드리지 않아(아래 if문 참고)
     * 낙관적 락만으로는 재시도 없이 그 자리에서 확정 응답을 주기 어렵다. 그래서 동시
     * 체결(OrderExecutionService.execute)과의 경합을 이 비관적 락으로 순서를 맞춰 막는다 —
     * 두 트랜잭션이 겹치면 나중에 락을 얻는 쪽이 먼저 커밋된 쪽의 최신 status를 보고 정확히
     * 반응한다(이미 처리된 주문이면 ORDER_ALREADY_PROCESSED로 응답).
     *
     * 계좌 정지(SUSPENDED) 시 로그인/조회는 가능하지만 거래 관련 행위는 전부 막는다는 정책이라,
     * 새 주문 생성뿐 아니라 기존 주문 취소도 거래 행위로 보고 createMarketOrder()/
     * createLimitOrder()와 동일하게 진입 시 차단한다.
     */
    @Transactional
    public void cancelOrder(Long userId, Long orderId) {
        Account account = accountRepository.findByUserId(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.ACCOUNT_NOT_FOUND));
        if (account.getStatus() == AccountStatus.SUSPENDED) {
            throw new CustomException(ErrorCode.ACCOUNT_SUSPENDED);
        }

        Order order = orderRepository.findByOrderIdAndAccountIdForUpdate(orderId, account.getAccountId())
                .orElseThrow(() -> new CustomException(ErrorCode.ORDER_NOT_FOUND));

        if (order.getStatus() != OrderStatus.PENDING) {
            throw new CustomException(ErrorCode.ORDER_ALREADY_PROCESSED);
        }

        if (order.getOrderType() == OrderType.BUY) {
            account.unfreezeForOrder(order.getOrderPrice() * order.getQuantity());
        }

        order.cancel();
        // createLimitOrder()와 같은 이유로, 이 트랜잭션이 실제로 커밋된 뒤에만 Redis에서 지운다.
        // 여기서 바로 지우면 이후 커밋이 실패(롤백)할 때 DB에는 여전히 PENDING인 주문이 Redis
        // pending:orders에서만 사라져 다시는 체결 대상이 되지 못하는 문제가 생긴다.
        registerAfterCommit(() -> redisPendingOrderService.removePendingOrder(order.getStockCode(), order.getOrderId()));
    }

    /**
     * 현재 진행 중인 트랜잭션이 실제로 커밋된 뒤에만 Redis 반영 작업을 실행하도록 등록한다.
     * createLimitOrder()/cancelOrder()가 실제 서비스에서 호출될 때는 항상 Spring이 관리하는
     * @Transactional 안이라 트랜잭션 동기화가 활성화돼 있다. 다만 단위 테스트처럼 실제 트랜잭션
     * 매니저 없이 이 메서드를 직접 호출하는 경우(동기화 비활성) TransactionSynchronizationManager.
     * registerSynchronization()이 예외를 던지므로, 그 경우에는 즉시 실행하는 것으로 대체한다.
     */
    private void registerAfterCommit(Runnable afterCommitTask) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            afterCommitTask.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                afterCommitTask.run();
            }
        });
    }
}
