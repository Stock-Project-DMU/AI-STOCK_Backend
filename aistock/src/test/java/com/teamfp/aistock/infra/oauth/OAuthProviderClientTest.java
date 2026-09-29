package com.teamfp.aistock.infra.oauth;

import com.teamfp.aistock.domain.user.entity.SocialProvider;
import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OAuthProviderClientTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void kakaoAccountWithoutEmailCanUseProviderIdentity() {
        var profile = objectMapper.readTree("""
                {"id":12345,"kakao_account":{"profile":{"nickname":"테스터"},"email_needs_agreement":true}}
                """);

        var user = OAuthProviderClient.mapUserInfo(SocialProvider.KAKAO, profile);

        assertThat(user.getProviderId()).isEqualTo("12345");
        assertThat(user.getEmail()).isNull();
        assertThat(user.getName()).isEqualTo("테스터");
    }

    @Test
    void unverifiedKakaoEmailCannotLinkExistingAccountByEmail() {
        var profile = objectMapper.readTree("""
                {"id":12345,"kakao_account":{"email":"owner@example.com","is_email_valid":true,"is_email_verified":false}}
                """);

        var user = OAuthProviderClient.mapUserInfo(SocialProvider.KAKAO, profile);

        assertThat(user.getEmail()).isNull();
    }

    @Test
    void verifiedKakaoEmailIsPreserved() {
        var profile = objectMapper.readTree("""
                {"id":12345,"kakao_account":{"email":"USER@EXAMPLE.COM","is_email_valid":true,"is_email_verified":true}}
                """);

        var user = OAuthProviderClient.mapUserInfo(SocialProvider.KAKAO, profile);

        assertThat(user.getEmail()).isEqualTo("user@example.com");
    }

    @Test
    void missingProviderIdIsRejected() {
        var profile = objectMapper.readTree("""
                {"kakao_account":{"profile":{"nickname":"테스터"}}}
                """);

        assertThatThrownBy(() -> OAuthProviderClient.mapUserInfo(SocialProvider.KAKAO, profile))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.OAUTH_PROVIDER_RESPONSE_INVALID);
    }

    @Test
    void missingGoogleAndNaverProviderIdsAreRejected() {
        var google = objectMapper.readTree("""
                {"email":"owner@example.com","email_verified":true}
                """);
        var naver = objectMapper.readTree("""
                {"resultcode":"00","response":{"email":"owner@example.com"}}
                """);

        assertThatThrownBy(() -> OAuthProviderClient.mapUserInfo(SocialProvider.GOOGLE, google))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.OAUTH_PROVIDER_RESPONSE_INVALID);
        assertThatThrownBy(() -> OAuthProviderClient.mapUserInfo(SocialProvider.NAVER, naver))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.OAUTH_PROVIDER_RESPONSE_INVALID);
    }

    @Test
    void failedNaverResponseIsRejectedEvenIfItContainsAnId() {
        var profile = objectMapper.readTree("""
                {"resultcode":"01","response":{"id":"naver-id"}}
                """);

        assertThatThrownBy(() -> OAuthProviderClient.mapUserInfo(SocialProvider.NAVER, profile))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.OAUTH_PROVIDER_RESPONSE_INVALID);
    }

    @Test
    void successfulNaverResponseWithoutEmailKeepsProviderIdentity() {
        var profile = objectMapper.readTree("""
                {"resultcode":"00","response":{"id":"naver-id","name":"테스터"}}
                """);

        var user = OAuthProviderClient.mapUserInfo(SocialProvider.NAVER, profile);

        assertThat(user.getProviderId()).isEqualTo("naver-id");
        assertThat(user.getEmail()).isNull();
    }

    @Test
    void unverifiedGoogleEmailIsNotUsedForAccountLinking() {
        var profile = objectMapper.readTree("""
                {"sub":"google-id","email":"owner@example.com","email_verified":false,"name":"테스터"}
                """);

        var user = OAuthProviderClient.mapUserInfo(SocialProvider.GOOGLE, profile);

        assertThat(user.getProviderId()).isEqualTo("google-id");
        assertThat(user.getEmail()).isNull();
        assertThat(user.getName()).isEqualTo("테스터");
    }
}
