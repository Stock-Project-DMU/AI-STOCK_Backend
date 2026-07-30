package com.teamfp.aistock.domain.account.dto.response;

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
}
