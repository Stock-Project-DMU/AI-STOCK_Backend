package com.teamfp.aistock.infra.ls.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * LS증권 Open API 접근토큰 발급(POST /oauth2/token) 응답 DTO.
 */
@Getter
@NoArgsConstructor
public class LsTokenResponse {

    @JsonProperty("access_token")
    private String accessToken;

    @JsonProperty("token_type")
    private String tokenType;

    @JsonProperty("expires_in")
    private long expiresIn;

    private String scope;
}
