package com.teamfp.aistock.domain.admin.dto.response;

import java.time.LocalDateTime;

import com.teamfp.aistock.domain.user.entity.Role;
import com.teamfp.aistock.domain.user.entity.User;
import com.teamfp.aistock.domain.user.entity.UserStatus;

/**
 * 관리자 — 사용자 목록(페이징) 조회 응답 DTO. 목록 화면에서는 자산현황/보유종목/거래내역까지
 * 필요 없으므로 기본정보만 담는다(상세 화면은 AdminUserDetailResponse가 별도로 담당).
 */
public record AdminUserListResponse(
        Long userId,
        String loginId,
        String name,
        String email,
        Role role,
        UserStatus status,
        LocalDateTime createdAt
) {

    public static AdminUserListResponse from(User user) {
        return new AdminUserListResponse(
                user.getUserId(),
                user.getLoginId(),
                user.getName(),
                user.getEmail(),
                user.getRole(),
                user.getStatus(),
                user.getCreatedAt()
        );
    }
}
