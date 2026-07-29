package com.teamfp.aistock.domain.auth.dto.response;

import com.teamfp.aistock.domain.user.entity.Role;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@AllArgsConstructor
public class SignupResponse {
    private Long userId;
    private String loginId;
    private Role role;
}
