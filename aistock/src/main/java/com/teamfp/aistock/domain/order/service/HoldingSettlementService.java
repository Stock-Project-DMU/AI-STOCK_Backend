package com.teamfp.aistock.domain.order.service;

import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.teamfp.aistock.domain.account.entity.Account;
import com.teamfp.aistock.domain.order.entity.Holding;
import com.teamfp.aistock.domain.order.repository.HoldingRepository;
import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

/**
 * 매수/매도 체결 시 보유종목(Holding) 반영을 담당한다.
 *
 * 시장가 체결(OrderService)과 지정가 체결(OrderExecutionService)이 각자 갖고 있던 동일한
 * 보유종목 갱신 로직을 하나로 합쳤다. 잔고(balance/frozenBalance) 반영과 "보유수량이 충분한지"
 * 검증 책임은 호출부마다 의미가 달라(시장가는 즉시 예외, 지정가 체결은 실패 시 주문 취소)
 * 그대로 각 서비스에 남겨두고, 두 곳에서 완전히 동일했던 부분만 이 클래스로 옮겼다.
 */
@Service
@RequiredArgsConstructor
public class HoldingSettlementService {

    private final HoldingRepository holdingRepository;

    // holdings.uq_account_stock — 같은 계좌·종목의 최초 매수가 동시에 두 건 이상 체결될 때
    // 걸리는 유니크 제약. DataIntegrityViolationException의 원인이 이 제약인지 메시지로
    // 구분한다(정확한 제약명 매칭이라 다른 원인의 저장 실패와 섞이지 않는다).
    private static final String UNIQUE_CONSTRAINT_NAME = "uq_account_stock";

    /**
     * 매수 체결분을 보유종목에 반영한다 — 이미 보유 중이면 수량을 더하고 평단가를 가중평균으로
     * 재계산하며, 처음 보유하는 종목이면 새 행을 만든다.
     *
     * 같은 계좌·종목의 최초 매수가 동시에 두 건 이상 체결되면 holdings.uq_account_stock 유니크
     * 제약에 걸릴 수 있는데, 이는 재시도하면 해소되는 순수 타이밍 경합이라 재시도 안내
     * 에러(OPTIMISTIC_LOCK_CONFLICT)로 변환해 던진다. 그 외의 이유로 저장이 실패한 경우(예:
     * stockName이 컬럼 길이를 초과하는 등 재시도해도 절대 해소되지 않는 데이터 문제)까지
     * OPTIMISTIC_LOCK_CONFLICT로 묶어버리면, 호출부(OrderExecutionService.checkAndExecute)가
     * "재시도하면 되는 충돌"로 오인해 매 tick 똑같이 실패하는 주문을 영원히 재시도만 하게
     * 되므로, 그 경우는 원래 예외를 그대로 던져 호출부의 일반 실패 처리(대기 목록 제거 +
     * 에러 로그)로 넘긴다.
     */
    public void increaseOrCreate(Account account, String stockCode, String stockName, int quantity, long execPrice) {
        Optional<Holding> holdingOpt = holdingRepository.findByAccountIdAndStockCode(account.getAccountId(), stockCode);
        if (holdingOpt.isPresent()) {
            holdingOpt.get().increase(quantity, execPrice);
            return;
        }

        try {
            holdingRepository.save(Holding.builder()
                    .account(account)
                    .stockCode(stockCode)
                    .stockName(stockName)
                    .quantity(quantity)
                    .avgPrice(execPrice)
                    .build());
        } catch (DataIntegrityViolationException e) {
            if (isUniqueConstraintViolation(e)) {
                throw new CustomException(ErrorCode.OPTIMISTIC_LOCK_CONFLICT, e);
            }
            throw e;
        }
    }

    private boolean isUniqueConstraintViolation(DataIntegrityViolationException e) {
        Throwable cause = e.getMostSpecificCause();
        return cause.getMessage() != null && cause.getMessage().contains(UNIQUE_CONSTRAINT_NAME);
    }

    /**
     * 매도 체결분만큼 보유수량을 차감한다. 전량 매도로 0이 되면 행 자체를 삭제한다 —
     * 보유종목 목록 조회 시 "안 갖고 있는 종목"이 섞여 나오는 것을 막기 위함이다.
     *
     * 매수와 달리 "보유수량이 충분한지" 검증은 호출부의 책임이다 — 이미 검증을 마치고
     * 조회해둔 holding만 이 메서드로 넘어온다고 전제한다.
     */
    public void decrease(Holding holding, int quantity) {
        holding.decrease(quantity);
        if (holding.getQuantity() == 0) {
            holdingRepository.delete(holding);
        }
    }
}
