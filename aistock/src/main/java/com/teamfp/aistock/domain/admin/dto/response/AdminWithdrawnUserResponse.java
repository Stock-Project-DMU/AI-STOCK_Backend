package com.teamfp.aistock.domain.admin.dto.response;

import java.time.LocalDateTime;

import com.teamfp.aistock.domain.user.entity.User;

/**
 * 관리자 — 탈퇴 회원 조회 응답 DTO(ADMIN_API_BACKEND_HANDOFF.md 5.4 옵션2). `User.deactivate()`가
 * loginId/name/email을 이미 익명화(deleted_N/탈퇴회원/null)했으므로 이 DTO도 그 익명화된 값을
 * 그대로 노출한다 — 탈퇴 전 원본 개인정보는 애초에 남아있지 않다.
 */
public record AdminWithdrawnUserResponse(
        Long userId,
        String loginId,
        String name,
        LocalDateTime deletedAt
) {

    public static AdminWithdrawnUserResponse from(User user) {
        return new AdminWithdrawnUserResponse(
                user.getUserId(),
                user.getLoginId(),
                user.getName(),
                user.getDeletedAt()
        );
    }
}
