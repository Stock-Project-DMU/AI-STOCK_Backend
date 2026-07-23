package com.teamfp.aistock.infra.oauth;

import com.teamfp.aistock.domain.user.entity.SocialProvider;
import com.teamfp.aistock.infra.oauth.dto.SocialUserDto;

public interface OAuthClient {
    SocialProvider getProvider();
    SocialUserDto getUserInfo(String code);
}
