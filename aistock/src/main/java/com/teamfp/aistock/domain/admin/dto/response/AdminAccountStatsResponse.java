package com.teamfp.aistock.domain.admin.dto.response;

/**
 * 관리자 계좌 상세의 누적 통계(feat/admin-improvements).
 * - totalCharged: 지금까지 들어온 충전 합계(셀프 충전 + 요청 승인·관리자 지급). 계좌 개설 지급·이자는 제외
 * - totalDeducted: 지금까지 빠져나간 차감 합계(관리자 차감 + 관리자 계정 셀프 차감, 양수로 표시)
 * - totalFee: 지금까지 낸 매도 거래 수수료 합계(양수로 표시)
 * - realizedProfit: 매도로 확정된 실현 손익 합계(수수료 반영, 마이페이지 실현 손익과 같은 계산). 체결 기록이 맞지 않아
 *   계산할 수 없으면 null
 */
public record AdminAccountStatsResponse(
        long totalCharged,
        long totalDeducted,
        long totalFee,
        Long realizedProfit
) {
}
