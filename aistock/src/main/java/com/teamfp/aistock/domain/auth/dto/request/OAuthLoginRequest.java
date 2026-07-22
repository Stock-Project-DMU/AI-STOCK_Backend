package com.teamfp.aistock.domain.auth.dto.request;

import com.teamfp.aistock.domain.user.entity.SocialProvider;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class OAuthLoginRequest {

    @NotNull(message = "소셜 로그인 제공자는 필수 입력 값입니다.")
    private SocialProvider provider;

    @NotBlank(message = "인증 코드는 필수 입력 값입니다.")
    private String code;
}
