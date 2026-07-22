package com.teamfp.aistock.infra.oauth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class GoogleUserInfo {

    @JsonProperty("id")
    private String id; // 구글 고유 식별값 (문자열)

    @JsonProperty("email")
    private String email;

    @JsonProperty("name")
    private String name; // 사용자 이름

    // 공통 규격인 SocialUserInfo로 변환
    public SocialUserInfo toSocialUserInfo() {
        return new SocialUserInfo(id, email, name);
    }
}
