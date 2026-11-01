package com.teamfp.aistock.domain.user.dto.response;

import com.teamfp.aistock.domain.user.entity.Role;
import com.teamfp.aistock.domain.user.entity.User;
import com.teamfp.aistock.domain.user.entity.UserStatus;

public record UserInfoResponse(
        Long userId,
        String loginId,
        String name,
        String email,
        Role role,
        UserStatus status,
        java.time.LocalDate birthdate,
        // 연동된 SNS 목록(fix/site-polish) — 마이페이지 가입 방식 표시용. loginId가 있으면 일반 가입, 없으면 이 목록의 SNS로
        // 가입한 회원이다. 일반 가입 회원은 보통 빈 목록이다.
        java.util.List<com.teamfp.aistock.domain.user.entity.SocialProvider> socialProviders
) {

    public static UserInfoResponse from(User user, java.util.List<com.teamfp.aistock.domain.user.entity.SocialProvider> socialProviders) {
        return new UserInfoResponse(
                user.getUserId(),
                user.getLoginId(),
                user.getName(),
                user.getEmail(),
                user.getRole(),
                user.getStatus(),
                user.getBirthdate(),
                socialProviders
        );
    }
}
