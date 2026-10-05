package com.teamfp.aistock.domain.account.dto.response;

import java.util.List;

import com.teamfp.aistock.domain.account.entity.Account;
import com.teamfp.aistock.domain.order.dto.HoldingValuationDto;

/**
 * 계좌 수익률 조회 응답 DTO. 계좌 A/B/C는 서로 독립된 영역이라 항상 계좌 하나 단위로 계산한다
 * (schema.sql "총 자산 계산 참고" — 계좌 단위 계산).
 */
public record ProfitResponse(
        long totalAsset,
        long profitAmount,
        double profitRate
) {

    public static ProfitResponse of(long totalAsset, long profitAmount, double profitRate) {
        return new ProfitResponse(totalAsset, profitAmount, profitRate);
    }

    /**
     * 계좌 하나의 총 자산·평가 손익·수익률 계산. 총 자산 = 현금(balance + frozenBalance) + 보유 종목 평가액
     * (현재가 × 수량), 손익 = 총 자산 - baseBalance(충전·이자로만 오르는 원금 기준값). 마이페이지 수익률
     * (AccountService.getProfit)과 관리자 계좌·회원 상세(feat/admin-improvements)가 같은 식을 쓰도록 여기 한 곳에 둔다.
     */
    public static ProfitResponse calculate(Account account, List<HoldingValuationDto> valuations) {
        long stockValuation = valuations.stream()
                .mapToLong(valuation -> valuation.currentPrice() * valuation.quantity())
                .sum();
        long totalAsset = account.getBalance() + account.getFrozenBalance() + stockValuation;
        long profitAmount = totalAsset - account.getBaseBalance();
        double profitRate = account.getBaseBalance() == 0
                ? 0.0
                : profitAmount * 100.0 / account.getBaseBalance();
        return of(totalAsset, profitAmount, profitRate);
    }
}
