package com.teamfp.aistock.infra.oauth;

import com.teamfp.aistock.domain.user.entity.SocialProvider;
import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;
import com.teamfp.aistock.infra.oauth.dto.SocialUserDto;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Locale;

/** 인증 코드/토큰/비밀키를 로그에 남기지 않습니다. */
@Component @RequiredArgsConstructor
public class OAuthProviderClient {
    private final Environment environment;

    private String setting(SocialProvider provider, String suffix) {
        return environment.getProperty(provider.name() + "_OAUTH_" + suffix, "");
    }

    private void requireConfigured(SocialProvider provider) {
        if (setting(provider, "CLIENT_ID").isBlank() || setting(provider, "REDIRECT_URI").isBlank()
                || (provider != SocialProvider.KAKAO && setting(provider, "CLIENT_SECRET").isBlank()))
            throw new CustomException(ErrorCode.OAUTH_NOT_CONFIGURED);
    }

    public String authorizationUrl(SocialProvider provider, String state) {
        requireConfigured(provider);
        String endpoint = switch (provider) {
            case GOOGLE -> "https://accounts.google.com/o/oauth2/v2/auth";
            case NAVER -> "https://nid.naver.com/oauth2.0/authorize";
            case KAKAO -> "https://kauth.kakao.com/oauth/authorize";
        };
        var uri = UriComponentsBuilder.fromUriString(endpoint).queryParam("response_type", "code")
                .queryParam("client_id", setting(provider, "CLIENT_ID"))
                .queryParam("redirect_uri", setting(provider, "REDIRECT_URI"))
                .queryParam("state", state);
        if (provider == SocialProvider.GOOGLE) uri.queryParam("scope", "openid email profile");
        return uri.build().encode().toUriString();
    }

    public SocialUserDto getUserInfo(SocialProvider provider, String code, String state) {
        requireConfigured(provider);
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(10));
        var client = RestClient.builder().requestFactory(factory).build();
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", setting(provider, "CLIENT_ID"));
        if (!setting(provider, "CLIENT_SECRET").isBlank()) form.add("client_secret", setting(provider, "CLIENT_SECRET"));
        form.add("redirect_uri", setting(provider, "REDIRECT_URI"));
        form.add("code", code);
        if (state != null) form.add("state", state);
        String tokenUrl = switch (provider) {
            case GOOGLE -> "https://oauth2.googleapis.com/token";
            case NAVER -> "https://nid.naver.com/oauth2.0/token";
            case KAKAO -> "https://kauth.kakao.com/oauth/token";
        };
        String userUrl = switch (provider) {
            case GOOGLE -> "https://openidconnect.googleapis.com/v1/userinfo";
            case NAVER -> "https://openapi.naver.com/v1/nid/me";
            case KAKAO -> "https://kapi.kakao.com/v2/user/me";
        };
        try {
            JsonNode token = client.post().uri(tokenUrl).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form).retrieve().body(JsonNode.class);
            if (token == null || token.path("access_token").asText("").isBlank()) throw new CustomException(ErrorCode.INVALID_TOKEN);
            JsonNode profile = client.get().uri(userUrl).headers(headers -> headers.setBearerAuth(token.path("access_token").asText()))
                    .retrieve().body(JsonNode.class);
            if (profile == null) throw new CustomException(ErrorCode.INVALID_TOKEN);
            JsonNode account = provider == SocialProvider.NAVER ? profile.path("response")
                    : provider == SocialProvider.KAKAO ? profile.path("kakao_account") : profile;
            String id = provider == SocialProvider.GOOGLE ? profile.path("sub").asText("")
                    : provider == SocialProvider.KAKAO ? profile.path("id").asText("") : account.path("id").asText("");
            boolean verified = provider == SocialProvider.GOOGLE ? account.path("email_verified").asBoolean(false)
                    : provider == SocialProvider.KAKAO ? account.path("is_email_valid").asBoolean(false) && account.path("is_email_verified").asBoolean(false)
                    : "00".equals(profile.path("resultcode").asText(""));
            String email = verified ? account.path("email").asText("") : "";
            String name = provider == SocialProvider.KAKAO ? account.path("profile").path("nickname").asText("")
                    : account.path("name").asText("");
            if (id.isBlank() || email.isBlank()) throw new CustomException(ErrorCode.OAUTH_EMAIL_REQUIRED);
            return new SocialUserDto(id, email.toLowerCase(Locale.ROOT), name.isBlank() ? "회원" : name);
        } catch (CustomException e) {
            throw e;
        } catch (org.springframework.web.client.RestClientException e) {
            // 토큰 응답 본문이 예외 메시지에 포함될 수 있으므로 원문을 기록하지 않음
            throw new CustomException(ErrorCode.EXTERNAL_API_ERROR);
        }
    }
}
