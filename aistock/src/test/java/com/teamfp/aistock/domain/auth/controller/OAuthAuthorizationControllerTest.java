package com.teamfp.aistock.domain.auth.controller;
import com.teamfp.aistock.domain.user.entity.SocialProvider;
import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.infra.oauth.OAuthProviderClient;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class OAuthAuthorizationControllerTest {
    @Test void googleAcceptsExistingClientKeyNames() {
        var client = new OAuthProviderClient(new MockEnvironment()
                .withProperty("GOOGLE_CLIENT_ID", "test-client-id")
                .withProperty("GOOGLE_CLIENT_SECRET", "test-client-secret")
                .withProperty("GOOGLE_OAUTH_REDIRECT_URI", "http://localhost:3000/oauth/google/callback"));
        assertThat(client.authorizationUrl(SocialProvider.GOOGLE, "test-state"))
                .contains("client_id=test-client-id")
                .contains("redirect_uri=http://localhost:3000/oauth/google/callback");
    }
    @Test void missingProviderConfigurationCannotCreateFakeIdentity() {
        var client = new OAuthProviderClient(new MockEnvironment());
        assertThatThrownBy(() -> client.getUserInfo(SocialProvider.GOOGLE, "arbitrary-code", "state")).isInstanceOf(CustomException.class);
    }
    @Test void stateIsBoundToSessionProviderAndSingleUse() {
        var session = new MockHttpSession();
        session.setAttribute("oauth.GOOGLE", "secret");
        session.setAttribute("oauth.GOOGLE.expires", System.currentTimeMillis() + 10000);
        assertThatThrownBy(() -> OAuthAuthorizationController.consumeState(new MockHttpSession(), SocialProvider.GOOGLE, "secret")).isInstanceOf(CustomException.class);
        OAuthAuthorizationController.consumeState(session, SocialProvider.GOOGLE, "secret");
        assertThatThrownBy(() -> OAuthAuthorizationController.consumeState(session, SocialProvider.GOOGLE, "secret")).isInstanceOf(CustomException.class);
    }
    @Test void expiredStateIsRejected() {
        var session = new MockHttpSession();
        session.setAttribute("oauth.GOOGLE", "secret");
        session.setAttribute("oauth.GOOGLE.expires", 1L);
        assertThatThrownBy(() -> OAuthAuthorizationController.consumeState(session, SocialProvider.GOOGLE, "secret")).isInstanceOf(CustomException.class);
    }
}
