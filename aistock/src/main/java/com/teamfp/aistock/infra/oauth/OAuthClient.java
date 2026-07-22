package com.teamfp.aistock.infra.oauth;

import com.teamfp.aistock.domain.user.entity.SocialProvider;
import com.teamfp.aistock.infra.oauth.dto.SocialUserInfo;

public interface OAuthClient {
    SocialProvider getProvider();
    SocialUserInfo getUserInfo(String code);
}
