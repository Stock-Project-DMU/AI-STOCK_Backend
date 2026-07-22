package com.teamfp.aistock.infra.oauth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class KakaoUserInfo {

    @JsonProperty("id")
    private Long id; // 카카오 고유 식별값 (숫자)

    @JsonProperty("kakao_account")
    private KakaoAccount kakaoAccount;

    @Getter
    @NoArgsConstructor
    public static class KakaoAccount {
        @JsonProperty("email")
        private String email;

        @JsonProperty("profile")
        private Profile profile;

        @Getter
        @NoArgsConstructor
        public static class Profile {
            @JsonProperty("nickname")
            private String nickname;
        }
    }

    // 공통 규격인 SocialUserInfo로 변환
    public SocialUserInfo toSocialUserInfo() {
        String providerId = (id != null) ? String.valueOf(id) : null;
        String email = (kakaoAccount != null) ? kakaoAccount.getEmail() : null;
        String name = (kakaoAccount != null && kakaoAccount.getProfile() != null) 
                ? kakaoAccount.getProfile().getNickname() : "카카오회원";

        return new SocialUserInfo(providerId, email, name);
    }
}
