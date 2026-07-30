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
        UserStatus status
) {

    public static UserInfoResponse from(User user) {
        return new UserInfoResponse(
                user.getUserId(),
                user.getLoginId(),
                user.getName(),
                user.getEmail(),
                user.getRole(),
                user.getStatus()
        );
    }
}
