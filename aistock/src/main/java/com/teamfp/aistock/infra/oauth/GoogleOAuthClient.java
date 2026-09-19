package com.teamfp.aistock.infra.oauth;
import com.teamfp.aistock.domain.user.entity.SocialProvider;
import com.teamfp.aistock.infra.oauth.dto.SocialUserDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class GoogleOAuthClient implements OAuthClient {
    private final OAuthProviderClient oauthProviderClient;
    @Override public SocialProvider getProvider() { return SocialProvider.GOOGLE; }
    @Override public SocialUserDto getUserInfo(String code) { return getUserInfo(code, null); }
    @Override public SocialUserDto getUserInfo(String code, String state) {
        return oauthProviderClient.getUserInfo(getProvider(), code, state);
    }
}
