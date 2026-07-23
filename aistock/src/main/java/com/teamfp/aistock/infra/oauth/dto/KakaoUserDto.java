package com.teamfp.aistock.infra.oauth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KakaoUserDto {

    @JsonProperty("id")
    private Long id; // 카카오 고유 식별값 (숫자)

    @JsonProperty("kakao_account")
    private KakaoAccount kakaoAccount;

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KakaoAccount {
        @JsonProperty("email")
        private String email;

        @JsonProperty("profile")
        private Profile profile;

        @Getter
        @Builder
        @NoArgsConstructor
        @AllArgsConstructor
        public static class Profile {
            @JsonProperty("nickname")
            private String nickname;
        }
    }

    // 공통 규격인 SocialUserDto로 변환
    public SocialUserDto toSocialUserDto() {
        String providerId = (id != null) ? String.valueOf(id) : null;
        String email = (kakaoAccount != null) ? kakaoAccount.getEmail() : null;
        String name = (kakaoAccount != null && kakaoAccount.getProfile() != null) 
                ? kakaoAccount.getProfile().getNickname() : "카카오회원";

        return new SocialUserDto(providerId, email, name);
    }
}
