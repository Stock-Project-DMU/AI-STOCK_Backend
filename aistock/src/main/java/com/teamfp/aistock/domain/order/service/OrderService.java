package com.teamfp.aistock.domain.order.service;

import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.teamfp.aistock.domain.account.entity.Account;
import com.teamfp.aistock.domain.account.repository.AccountRepository;
import com.teamfp.aistock.domain.order.dto.request.CreateOrderRequest;
import com.teamfp.aistock.domain.order.dto.response.CreateOrderResponse;
import com.teamfp.aistock.domain.order.entity.Holding;
import com.teamfp.aistock.domain.order.entity.Order;
import com.teamfp.aistock.domain.order.entity.OrderType;
import com.teamfp.aistock.domain.order.entity.PriceType;
import com.teamfp.aistock.domain.order.repository.HoldingRepository;
import com.teamfp.aistock.domain.order.repository.OrderRepository;
import com.teamfp.aistock.domain.stock.dto.StockPriceDto;
import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;
import com.teamfp.aistock.global.redis.RedisStockCacheService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final HoldingRepository holdingRepository;
    private final AccountRepository accountRepository;
    // 현재가는 도메인 간 서비스 호출(domain.stock.StockService)이 아니라
    // global/redis 서비스를 직접 조회한다 — feature/stock-price가 아직 구현 전이라
    // StockService에 의존하면 이 기능이 그쪽 완료를 기다려야 하고, CLAUDE.md 7번
    // 규칙상 Redis 접근은 어차피 global/redis 서비스를 통해서만 하도록 되어 있어
    // 직접 주입이 규칙에도 어긋나지 않는다.
    private final RedisStockCacheService redisStockCacheService;

    /**
     * 현재가(시장가) 주문 — 잔고/보유수량을 확인한 뒤 실시간 현재가로 즉시 체결한다.
     * 지정가 주문(feature/order-limit)과 달리 pending:orders에 쌓아두지 않고
     * 이 메서드 안에서 매수/매도를 바로 완결한다.
     *
     * 이 메서드는 request.priceType()이 항상 MARKET이라고 전제한다 — LIMIT 요청을 막는 검증은
     * priceType으로 시장가/지정가를 분기하는 책임을 가진 OrderController에서 이미 처리한다.
     *
     * TODO(AccountStatus): NAMING.md 8-5 v8 항목에 따르면 진입 시 account.getStatus()가
     * SUSPENDED(관리자에 의한 계좌 거래 정지)면 CustomException(ErrorCode.ACCOUNT_SUSPENDED)를
     * 던져야 한다. 다만 Account.status 필드는 A의 admin-entity-update 브랜치가 아직
     * dev에 병합되지 않아 존재하지 않는다 — 병합 후 이 자리에 체크 로직을 추가한다.
     */
    @Transactional
    public CreateOrderResponse createMarketOrder(Long userId, CreateOrderRequest request) {
        Account account = accountRepository.findByUserId(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.ACCOUNT_NOT_FOUND));

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

        Optional<Holding> holding = findHolding(account, request.stockCode());
        if (holding.isPresent()) {
            // 이미 들고 있던 종목이면 수량을 더하고 평단가를 가중평균으로 재계산한다.
            holding.get().increase(request.quantity(), currentPrice);
        } else {
            // 처음 매수하는 종목이면 신규 보유종목 행을 만든다. 두 개의 최초 매수 요청이
            // 동시에 들어와 uq_account_stock 유니크 제약에 걸릴 수 있는 이유는
            // findByAccountIdAndStockCode()의 비관적 락 범위 설명(HoldingRepository 참고) —
            // 여기서는 그 경합만 좁게 잡아 재시도 안내 에러로 변환한다(전역으로 잡지 않는
            // 이유도 HoldingRepository 주석 참고).
            try {
                holdingRepository.save(Holding.builder()
                        .account(account)
                        .stockCode(request.stockCode())
                        .stockName(stockName)
                        .quantity(request.quantity())
                        .avgPrice(currentPrice)
                        .build());
            } catch (DataIntegrityViolationException e) {
                throw new CustomException(ErrorCode.OPTIMISTIC_LOCK_CONFLICT, e);
            }
        }
    }

    private void executeSell(Account account, CreateOrderRequest request, long totalAmount) {
        Holding holding = findHolding(account, request.stockCode())
                .orElseThrow(() -> new CustomException(ErrorCode.INSUFFICIENT_HOLDING));
        if (holding.getQuantity() < request.quantity()) {
            throw new CustomException(ErrorCode.INSUFFICIENT_HOLDING);
        }

        account.applySellOrder(totalAmount);
        holding.decrease(request.quantity());
        if (holding.getQuantity() == 0) {
            // 전량 매도한 경우 수량 0짜리 행을 남겨두지 않고 삭제한다 — 보유종목 목록 조회 시
            // "안 갖고 있는 종목"이 섞여 나오는 것을 막기 위함.
            holdingRepository.delete(holding);
        }
    }

    private Optional<Holding> findHolding(Account account, String stockCode) {
        return holdingRepository.findByAccountIdAndStockCode(account.getAccountId(), stockCode);
    }
}
