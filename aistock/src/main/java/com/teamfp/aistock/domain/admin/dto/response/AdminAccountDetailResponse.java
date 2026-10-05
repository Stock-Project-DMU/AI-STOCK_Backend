package com.teamfp.aistock.domain.admin.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.teamfp.aistock.domain.account.dto.response.ProfitResponse;
import com.teamfp.aistock.domain.account.entity.Account;
import com.teamfp.aistock.domain.account.entity.AccountStatus;
import com.teamfp.aistock.domain.order.dto.response.HoldingResponse;
import com.teamfp.aistock.domain.order.dto.response.OrderHistoryResponse;

/**
 * 관리자 계좌 상세(및 계좌 정지/해제·잔고 조정 결과) 응답 DTO. accountId/userName/accountNumber/balance/
 * frozenBalance/baseBalance/status는 기존 필드 그대로이고, feat/admin-improvements에서 계좌 하나를 한 화면에서
 * 파악할 수 있게 아래를 추가했다.
 *
 * - userId/loginId: 회원 상세로 이동·검색용
 * - accountName/openedAt: 계좌 이름·개설일
 * - chargeCount/maxChargeCount/unlimitedCharge: 셀프 충전 사용 횟수(관리자 계정 계좌는 무제한)
 * - interestRate/totalInterest: 예치금 연이율(%)·지금까지 받은 이자
 * - totalAsset/profitAmount/profitRate: ProfitResponse.calculate()와 같은 식의 총 자산·평가 손익·수익률(%)
 * - holdings: 보유 종목(종목·수량·평단가·현재가·평가손익)
 * - recentOrders/orderCount: 이 계좌의 최근 주문 AdminAccountService.RECENT_ORDER_LIMIT(20)건과 전체 주문 건수
 * - recentChargeRequests: 이 계좌의 최근 충전 요청 AdminAccountService.RECENT_CHARGE_REQUEST_LIMIT(10)건
 * - suspensionHistory: 계좌 정지·해제 이력(감사 로그 ACCOUNT_STATUS_CHANGE, 최신순 — 언제·누가·왜)
 * - stats: 누적 통계(총 충전·총 차감·총 수수료·실현 손익 합계, AdminAccountStatsResponse)
 * 잔고 변동 내역은 건수가 많아 여기 넣지 않고 GET /api/admin/accounts/{accountId}/transactions(페이지)로 본다.
 */
public record AdminAccountDetailResponse(
        Long accountId,
        Long userId,
        String loginId,
        String userName,
        String accountName,
        String accountNumber,
        LocalDate openedAt,
        long balance,
        long frozenBalance,
        long baseBalance,
        AccountStatus status,
        int chargeCount,
        int maxChargeCount,
        boolean unlimitedCharge,
        BigDecimal interestRate,
        long totalInterest,
        long totalAsset,
        long profitAmount,
        double profitRate,
        List<HoldingResponse> holdings,
        List<OrderHistoryResponse> recentOrders,
        long orderCount,
        List<AdminChargeRequestResponse> recentChargeRequests,
        List<AuditLogResponse> suspensionHistory,
        AdminAccountStatsResponse stats
) {

    public static AdminAccountDetailResponse of(Account account, List<HoldingResponse> holdings, ProfitResponse profit,
            List<OrderHistoryResponse> recentOrders, long orderCount, List<AdminChargeRequestResponse> recentChargeRequests,
            List<AuditLogResponse> suspensionHistory, AdminAccountStatsResponse stats) {
        return new AdminAccountDetailResponse(
                account.getAccountId(),
                account.getUser().getUserId(),
                account.getUser().getLoginId(),
                account.getUser().getName(),
                account.getAccountName(),
                account.getAccountNumber(),
                account.getOpenedAt(),
                account.getBalance(),
                account.getFrozenBalance(),
                account.getBaseBalance(),
                account.getStatus(),
                account.getChargeCount(),
                Account.MAX_CHARGE_COUNT,
                account.isChargeUnlimited(),
                account.getInterestRate(),
                account.getTotalInterest(),
                profit.totalAsset(),
                profit.profitAmount(),
                profit.profitRate(),
                holdings,
                recentOrders,
                orderCount,
                recentChargeRequests,
                suspensionHistory,
                stats
        );
    }
}
