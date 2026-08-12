package com.teamfp.aistock.domain.admin.dto.response;

import java.time.LocalDateTime;
import java.util.List;

import com.teamfp.aistock.domain.account.dto.response.AccountInfoResponse;
import com.teamfp.aistock.domain.order.dto.response.HoldingResponse;
import com.teamfp.aistock.domain.order.dto.response.OrderHistoryResponse;
import com.teamfp.aistock.domain.user.entity.Role;
import com.teamfp.aistock.domain.user.entity.User;
import com.teamfp.aistock.domain.user.entity.UserStatus;

/**
 * 관리자 — 사용자 상세 조회 응답 DTO. 기본정보 + 자산현황(accounts) + 보유종목(holdings) +
 * 거래내역(orders)을 한 번에 내려준다. 유저가 계좌를 여러 개(최대 3개) 가질 수 있어
 * accounts는 전체 계좌 리스트, holdings/orders는 그 계좌들을 전부 합산한 값이다
 * (개별 계좌 단위 조회는 feature/admin-account가 담당 — NAMING.md 8-17 참고).
 */
public record AdminUserDetailResponse(
        Long userId,
        String loginId,
        String name,
        String email,
        Role role,
        UserStatus status,
        LocalDateTime createdAt,
        List<AccountInfoResponse> accounts,
        List<HoldingResponse> holdings,
        List<OrderHistoryResponse> orders
) {

    public static AdminUserDetailResponse of(
            User user,
            List<AccountInfoResponse> accounts,
            List<HoldingResponse> holdings,
            List<OrderHistoryResponse> orders
    ) {
        return new AdminUserDetailResponse(
                user.getUserId(),
                user.getLoginId(),
                user.getName(),
                user.getEmail(),
                user.getRole(),
                user.getStatus(),
                user.getCreatedAt(),
                accounts,
                holdings,
                orders
        );
    }
}
