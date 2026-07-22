package com.teamfp.aistock.infra.oauth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class NaverUserInfo {

    @JsonProperty("resultcode")
    private String resultCode;

    @JsonProperty("message")
    private String message;

    @JsonProperty("response")
    private NaverResponse response;

    @Getter
    @NoArgsConstructor
    public static class NaverResponse {
        @JsonProperty("id")
        private String id; // 네이버 고유 식별값 (문자열)

        @JsonProperty("email")
        private String email;

        @JsonProperty("name")
        private String name; // 사용자 이름
    }

    // 공통 규격인 SocialUserInfo로 변환
    public SocialUserInfo toSocialUserInfo() {
        if (response == null) {
            return new SocialUserInfo(null, null, "네이버회원");
        }
        return new SocialUserInfo(response.getId(), response.getEmail(), response.getName());
    }
}
