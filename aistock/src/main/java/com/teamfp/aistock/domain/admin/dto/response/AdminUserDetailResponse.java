package com.teamfp.aistock.domain.admin.dto.response;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import com.teamfp.aistock.domain.account.dto.response.AccountInfoResponse;
import com.teamfp.aistock.domain.order.dto.response.HoldingResponse;
import com.teamfp.aistock.domain.order.dto.response.OrderHistoryResponse;
import com.teamfp.aistock.domain.stock.dto.response.RecentViewedResponse;
import com.teamfp.aistock.domain.stock.dto.response.WatchlistResponse;
import com.teamfp.aistock.domain.user.entity.Role;
import com.teamfp.aistock.domain.user.entity.UserStatus;

import lombok.Builder;

/**
 * 관리자 — 사용자 상세 조회 응답 DTO. 기본정보 + 자산현황(accounts) + 보유종목(holdings) +
 * 거래내역(orders)을 한 번에 내려준다. 유저가 계좌를 여러 개(최대 3개) 가질 수 있어
 * accounts는 전체 계좌 리스트, holdings/orders는 그 계좌들을 전부 합산한 값이다
 * (개별 계좌 단위 조회는 feature/admin-account가 담당 — NAMING.md 8-17 참고).
 *
 * feat/admin-improvements에서 회원 한 명을 한 화면에서 파악할 수 있게 아래를 추가했다(필드가 많아 빌더로 만든다 —
 * AdminUserService.buildDetail).
 * - birthdate/updatedAt/lastLoginAt: 개인정보·마지막 로그인 시각(컬럼 추가 전 회원은 다음 로그인 전까지 null)
 * - socialAccounts: 소셜 로그인 연동 목록(일반 가입자는 빈 목록) / investmentProfile: 투자 성향(설문 전이면 null)
 * - totalAsset/profitAmount/profitRate: 전체 계좌 합산 총 자산·평가 손익·수익률(%) — 계좌별 식은 ProfitResponse.calculate()
 * - realizedProfit: 매도로 확정된 실현 손익 합계(계산할 수 없으면 null)
 * - orders/orderCount: 최근 주문 AdminUserService.RECENT_ORDER_LIMIT(20)건과 전체 주문 건수
 * - recentChargeRequests: 최근 충전 요청 AdminUserService.RECENT_CHARGE_REQUEST_LIMIT(10)건
 * - inquiryCount/recentInquiries: 전체 문의 건수와 최근 문의 AdminUserService.RECENT_INQUIRY_LIMIT(5)건
 * - watchlist: 관심 종목 전체 / recentViewed: 최근 본 종목 AdminUserService.RECENT_VIEWED_LIMIT(10)건
 * - newsBriefingSetting: 뉴스 브리핑 설정(설정 안 했으면 null)
 * - suspensionHistory: 회원 정지·해제와 계좌(거래) 정지·해제 이력 전체(감사 로그, 최신순)
 * - adminActionCount/recentAdminActions: 이 회원·계좌·주문·충전 요청에 관리자가 한 처리(감사 로그) 건수와 최근
 *   AdminUserService.RECENT_ADMIN_ACTION_LIMIT(20)건
 * 목표 시뮬레이션·AI 재무설계 상담 내역·받은 알림은 관리자 판단에 필요 없거나 너무 많아 넣지 않는다.
 */
@Builder
public record AdminUserDetailResponse(
        Long userId,
        String loginId,
        String name,
        String email,
        LocalDate birthdate,
        Role role,
        UserStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime lastLoginAt,
        String suspensionReason,
        LocalDateTime suspendedUntil,
        List<AdminSocialAccountResponse> socialAccounts,
        AdminInvestmentProfileResponse investmentProfile,
        List<AccountInfoResponse> accounts,
        long totalAsset,
        long profitAmount,
        double profitRate,
        Long realizedProfit,
        List<HoldingResponse> holdings,
        List<OrderHistoryResponse> orders,
        long orderCount,
        List<AdminChargeRequestResponse> recentChargeRequests,
        long inquiryCount,
        List<AdminInquiryResponse> recentInquiries,
        List<WatchlistResponse> watchlist,
        List<RecentViewedResponse> recentViewed,
        AdminNewsBriefingSettingResponse newsBriefingSetting,
        List<AuditLogResponse> suspensionHistory,
        long adminActionCount,
        List<AuditLogResponse> recentAdminActions
) {
}
