package com.teamfp.aistock.infra.marketdata.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 외부 시세 데이터 제공사 Open API 접근토큰 발급(POST /oauth2/token) 응답 DTO.
 */
@Getter
@NoArgsConstructor
public class MarketDataTokenResponse {

    @JsonProperty("access_token")
    private String accessToken;

    @JsonProperty("token_type")
    private String tokenType;

    @JsonProperty("expires_in")
    private long expiresIn;

    private String scope;
}
