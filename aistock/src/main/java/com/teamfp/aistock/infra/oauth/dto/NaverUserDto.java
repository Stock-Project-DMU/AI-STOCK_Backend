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
public class NaverUserDto {

    @JsonProperty("resultcode")
    private String resultCode;

    @JsonProperty("message")
    private String message;

    @JsonProperty("response")
    private NaverResponse response;

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NaverResponse {
        @JsonProperty("id")
        private String id; // 네이버 고유 식별값 (문자열)

        @JsonProperty("email")
        private String email;

        @JsonProperty("name")
        private String name; // 사용자 이름
    }

    // 공통 규격인 SocialUserDto로 변환
    public SocialUserDto toSocialUserDto() {
        if (response == null) {
            return new SocialUserDto(null, null, "네이버회원");
        }
        return new SocialUserDto(response.getId(), response.getEmail(), response.getName());
    }
}
