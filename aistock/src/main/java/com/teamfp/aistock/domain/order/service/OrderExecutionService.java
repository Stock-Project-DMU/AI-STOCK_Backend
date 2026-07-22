package com.teamfp.aistock.domain.order.service;

import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.teamfp.aistock.domain.account.entity.Account;
import com.teamfp.aistock.domain.order.dto.PendingOrderDto;
import com.teamfp.aistock.domain.order.entity.Holding;
import com.teamfp.aistock.domain.order.entity.Order;
import com.teamfp.aistock.domain.order.entity.OrderStatus;
import com.teamfp.aistock.domain.order.entity.OrderType;
import com.teamfp.aistock.domain.order.repository.HoldingRepository;
import com.teamfp.aistock.domain.order.repository.OrderRepository;
import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;
import com.teamfp.aistock.global.redis.RedisPendingOrderService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 지정가(LIMIT) 미체결 주문의 체결을 담당하는 서비스.
 *
 * LS증권 WebSocket으로 tick(체결가)이 들어올 때마다 상위 계층(tick 처리 파이프라인, 아직 다른
 * 브랜치에서 구현 중)이 종목코드별로 checkAndExecute()를 호출하는 것을 전제로 한다. 이 클래스
 * 자체는 tick 수신 로직을 알지 못하고, "이 종목의 이 현재가로 체결 가능한 대기 주문이 있는지"만 책임진다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderExecutionService {

    private final OrderRepository orderRepository;
    private final HoldingRepository holdingRepository;
    private final RedisPendingOrderService redisPendingOrderService;
    private final HoldingSettlementService holdingSettlementService;

    // execute()의 @Transactional은 Spring AOP 프록시를 거쳐야만 실제로 트랜잭션을 연다.
    // checkAndExecute()가 같은 클래스 안에서 execute(...)를 그냥 호출하면(self-invocation)
    // 프록시를 우회해 트랜잭션이 전혀 열리지 않고, orderRepository.findById()가 끝나자마자
    // detached된 order.getAccount()(LAZY)를 건드리다 LazyInitializationException이 난다.
    // final 생성자 주입 대신 @Lazy 필드 주입으로 스프링이 만든 프록시(자기 자신)를 받아와
    // self.execute(...)로 호출해야 @Transactional이 실제로 적용된다.
    @Autowired
    @Lazy
    private OrderExecutionService self;

    /**
     * 특정 종목의 체결 tick을 받을 때마다 호출된다. Redis pending:orders:{stockCode}에 쌓인
     * 미체결 주문 전체를 훑어, 지정가 조건(매수는 현재가 <= 지정가, 매도는 현재가 >= 지정가)을
     * 만족하는 주문만 체결을 시도한다.
     *
     * 한 종목에 여러 미체결 주문이 있을 때 하나가 실패해도 나머지 주문 처리가 막히면 안 되므로,
     * 주문 하나하나를 개별 try-catch로 감싸 여기서 예외를 흡수한다.
     */
    public void checkAndExecute(String stockCode, long currentPrice) {
        List<PendingOrderDto> pendingOrders = redisPendingOrderService.getPendingOrders(stockCode);

        for (PendingOrderDto pendingOrder : pendingOrders) {
            if (!isConditionMet(pendingOrder, currentPrice)) {
                continue;
            }

            try {
                self.execute(pendingOrder, currentPrice);
                redisPendingOrderService.removePendingOrder(stockCode, pendingOrder.getOrderId());
            } catch (RuntimeException e) {
                if (isRetryableConflict(e)) {
                    // 같은 주문을 두고 다른 트랜잭션(중복 체결 시도, 사용자의 취소 요청)과 경합해
                    // 이번 시도가 졌다는 뜻이다 — Account/Order의 낙관적 락 충돌, 신규 보유종목
                    // 동시 매수로 인한 holdings.uq_account_stock 위반, Order 행 비관적 락 대기
                    // 타임아웃이 모두 여기 해당한다. 이 주문이 이미 최종 처리(체결/취소)됐는지는
                    // 이 시점에는 알 수 없으므로 Redis에서 지우지 않고 다음 tick에서 다시 시도한다
                    // — execute()는 재호출 시 주문 상태를 다시 읽어 이미 처리된 주문이면 아무 것도
                    // 하지 않고 조용히 리턴하는 멱등 동작을 하므로 재시도해도 안전하다.
                    log.warn("지정가 체결 중 동시성 충돌, 다음 tick에 재시도: orderId={}", pendingOrder.getOrderId(), e);
                } else {
                    // 경합이 아닌, 재시도해도 나아질지 알 수 없는 예상 못 한 실패다(주문 자체가
                    // 더 이상 유효하지 않은 상태 등). 여기서 잡지 않으면 checkAndExecute() 전체가
                    // 중단돼 이 tick에서 처리해야 할 나머지 대기 주문들까지 전부 처리되지 못한다 —
                    // 클래스 상단 Javadoc이 약속한 "하나의 주문 실패가 나머지를 막지 않는다"를
                    // 지키려면 반드시 여기서도 흡수해야 한다. 대기 목록에 계속 남겨두면 매 tick마다
                    // 똑같은 실패를 반복하므로 제거한다.
                    log.error("지정가 체결 실패, 대기 목록에서 제거: orderId={}", pendingOrder.getOrderId(), e);
                    redisPendingOrderService.removePendingOrder(stockCode, pendingOrder.getOrderId());
                }
            }
        }
    }

    /**
     * checkAndExecute()에서 잡은 예외가 "다른 트랜잭션과의 순수 타이밍 경합이라 다음 tick에
     * 재시도하면 되는지"를 판단한다. 세 가지 경우를 모두 재시도 대상으로 본다:
     * Account/Order의 낙관적 락 충돌(ObjectOptimisticLockingFailureException), 신규 보유종목
     * 동시 매수로 인한 holdings.uq_account_stock 위반(executeBuy()가 OPTIMISTIC_LOCK_CONFLICT로
     * 변환해 던짐), Order 행 비관적 락(findByIdForUpdate) 대기 타임아웃
     * (PessimisticLockingFailureException — execute()와 OrderService.cancelOrder()가 같은
     * 주문을 두고 경합할 때 발생할 수 있다). 이 셋을 재시도 불가로 잘못 취급하면, 사실은
     * 여전히 PENDING인 주문이 대기 목록에서 영구히 빠져 서버 재시작 전까지 체결 대상이 되지
     * 못하는 문제가 생긴다.
     */
    private boolean isRetryableConflict(RuntimeException e) {
        if (e instanceof ObjectOptimisticLockingFailureException || e instanceof PessimisticLockingFailureException) {
            return true;
        }
        return e instanceof CustomException ce && ce.getErrorCode() == ErrorCode.OPTIMISTIC_LOCK_CONFLICT;
    }

    private boolean isConditionMet(PendingOrderDto pendingOrder, long currentPrice) {
        Long limitPrice = pendingOrder.getLimitPrice();
        if (limitPrice == null) {
            // 정상 흐름(createLimitOrder)에서는 limitPrice가 항상 채워지지만, Redis에 저장된
            // JSON이라 스키마 변경/수동 편집 등으로 값이 비어 있을 가능성을 배제할 수 없다.
            // 언박싱(currentPrice <= limitPrice) 시 NPE로 이 tick의 나머지 주문 처리까지
            // 막히는 것을 막기 위해 방어적으로 "조건 미충족"으로 처리하고 넘어간다.
            log.warn("PendingOrderDto.limitPrice가 null이라 체결 조건 판정을 건너뜀: orderId={}", pendingOrder.getOrderId());
            return false;
        }

        boolean isBuy = OrderType.BUY.name().equals(pendingOrder.getOrderType());
        boolean isSell = OrderType.SELL.name().equals(pendingOrder.getOrderType());
        return (isBuy && currentPrice <= limitPrice)
                || (isSell && currentPrice >= limitPrice);
    }

    /**
     * 주문 한 건을 실제 체결가(currentPrice)로 체결한다.
     *
     * 이미 다른 트랜잭션이 먼저 처리해 상태가 PENDING이 아니게 됐다면(체결 완료 또는 취소) 아무 것도
     * 하지 않고 조용히 리턴한다 — checkAndExecute()가 낙관적 락 충돌 이후 재시도할 때 이 메서드가
     * 멱등하게 동작해야 하기 때문이다.
     *
     * 매수 체결은 Account.frozenBalance/balance를 반드시 수정하므로 사용자의 동시 취소 요청과는
     * Account.version 낙관적 락으로도 보호되지만, 매도 취소는 Account를 건드리지 않아(OrderService.
     * cancelOrder 참고) 그 보호를 받지 못한다. 그래서 이 Order 행 자체를 findByIdForUpdate로
     * 비관적 락(SELECT ... FOR UPDATE)을 걸어 조회한다 — OrderService.cancelOrder()도 같은 주문
     * 행을 findByOrderIdAndUserIdForUpdate로 잠그므로(WHERE 절만 다를 뿐 같은 Order 행에
     * PESSIMISTIC_WRITE), 두 트랜잭션은 매수/매도 무관하게 항상
     * 순서대로만 처리되고 나중에 락을 얻는 쪽은 먼저 처리된 최신 status를 보게 된다.
     */
    @Transactional
    public void execute(PendingOrderDto pendingOrder, long currentPrice) {
        Order order = orderRepository.findByIdForUpdate(pendingOrder.getOrderId())
                .orElseThrow(() -> new CustomException(ErrorCode.ORDER_NOT_FOUND));

        if (order.getStatus() != OrderStatus.PENDING) {
            return;
        }

        Account account = order.getAccount();

        if (order.getOrderType() == OrderType.BUY) {
            executeBuy(order, account, currentPrice);
        } else {
            if (!executeSell(order, account, currentPrice)) {
                // 체결 시점에 보유수량이 부족해졌다(예: 같은 종목을 다른 주문으로 먼저 매도함).
                // 재시도해도 상황이 나아지지 않으므로 체결 대신 주문을 취소 처리한다.
                order.cancel();
                log.warn("지정가 매도 체결 취소 - 보유수량 부족: orderId={}", order.getOrderId());
                return;
            }
        }

        order.execute(currentPrice);
    }

    private void executeBuy(Order order, Account account, long currentPrice) {
        long frozenAmount = order.getOrderPrice() * order.getQuantity();
        long actualAmount = currentPrice * order.getQuantity();
        // 지정가 매수는 "현재가 <= 지정가"일 때만 체결되므로 actualAmount는 항상 frozenAmount
        // 이하다 — 주문 등록 시 이미 최대 금액을 frozenBalance로 묶어뒀으므로 잔고 부족 걱정 없이
        // 그대로 정산(해제 + 차액 환급)만 하면 된다.
        account.settleFrozenOrder(frozenAmount, actualAmount);
        holdingSettlementService.increaseOrCreate(account, order.getStockCode(), order.getStockName(), order.getQuantity(), currentPrice);
    }

    /**
     * @return 체결에 성공하면 true, 체결 시점 기준 보유수량이 부족해 체결할 수 없으면 false
     */
    private boolean executeSell(Order order, Account account, long currentPrice) {
        Optional<Holding> holdingOpt = holdingRepository.findByAccountIdAndStockCode(account.getAccountId(), order.getStockCode());
        if (holdingOpt.isEmpty() || holdingOpt.get().getQuantity() < order.getQuantity()) {
            return false;
        }

        holdingSettlementService.decrease(holdingOpt.get(), order.getQuantity());
        account.applySellOrder(currentPrice * order.getQuantity());
        return true;
    }
}
