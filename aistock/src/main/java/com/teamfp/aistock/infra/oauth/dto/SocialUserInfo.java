package com.teamfp.aistock.infra.oauth.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class SocialUserInfo {
    private String providerId;
    private String email;
    private String name;
}
