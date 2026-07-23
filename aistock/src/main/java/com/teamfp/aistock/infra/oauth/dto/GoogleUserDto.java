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
public class GoogleUserDto {

    @JsonProperty("id")
    private String id; // 구글 고유 식별값 (문자열)

    @JsonProperty("email")
    private String email;

    @JsonProperty("name")
    private String name; // 사용자 이름

    // 공통 규격인 SocialUserDto로 변환
    public SocialUserDto toSocialUserDto() {
        return new SocialUserDto(id, email, name);
    }
}
