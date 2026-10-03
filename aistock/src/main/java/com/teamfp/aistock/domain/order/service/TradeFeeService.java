package com.teamfp.aistock.domain.order.service;

import org.springframework.stereotype.Service;

import com.teamfp.aistock.domain.account.entity.Account;
import com.teamfp.aistock.domain.account.entity.AccountTransactionType;
import com.teamfp.aistock.domain.account.service.AccountTransactionService;
import com.teamfp.aistock.domain.order.entity.Order;

import lombok.RequiredArgsConstructor;

/**
 * 매도 체결 거래 수수료 차감(feature/mypage-improvement). 시장가 매도(OrderService)와 지정가 매도
 * (OrderExecutionService)가 같은 계산·기록을 각자 복붙하면 한쪽만 바뀌었을 때 잔고에서 빠진 수수료와
 * Order.fee(주문내역에 보이는 수수료)가 어긋날 수 있어 이 서비스 하나로 모았다(코드리뷰 반영).
 *
 * 금액은 항상 order.getFee()를 쓴다 — Order.execute()가 체결 시점에 Order.calculateSellFee()로
 * 채워 두므로, 반드시 order.execute() 이후에 호출해야 한다.
 */
@Service
@RequiredArgsConstructor
public class TradeFeeService {

    private final AccountTransactionService accountTransactionService;

    public void applySellFee(Account account, Order order) {
        long fee = order.getFee();
        if (fee <= 0) {
            return;
        }
        long balanceBefore = account.getBalance();
        account.applyTradeFee(fee);
        accountTransactionService.record(account, AccountTransactionType.TRADE_FEE, -fee, balanceBefore,
                order.getOrderId(), null, null, order.describeStockAndQuantity() + " 매도 거래 수수료(0.1%)");
    }
}
