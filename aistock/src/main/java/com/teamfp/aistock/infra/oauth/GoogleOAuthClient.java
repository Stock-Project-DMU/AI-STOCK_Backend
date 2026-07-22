package com.teamfp.aistock.infra.oauth;

import com.teamfp.aistock.domain.user.entity.SocialProvider;
import com.teamfp.aistock.infra.oauth.dto.GoogleUserInfo;
import com.teamfp.aistock.infra.oauth.dto.SocialUserInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class GoogleOAuthClient implements OAuthClient {

    @Override
    public SocialProvider getProvider() {
        return SocialProvider.GOOGLE;
    }

    @Override
    public SocialUserInfo getUserInfo(String code) {
        log.info("구글 소셜 로그인 유저 정보 획득 시작 - code: {}", code);
        
        // TODO: 실제 구글 토큰 발급 및 사용자 프로필 API 조회 로직 구현부
        // RestTemplate 또는 WebClient를 사용해 아래 구조로 조회하여 파싱합니다.
        // ResponseEntity<GoogleUserInfo> response = restTemplate.exchange(userInfoUrl, HttpMethod.GET, entity, GoogleUserInfo.class);
        // return response.getBody().toSocialUserInfo();

        // 현재 단계에서는 Mock 데이터를 생성해 실제 타입 안정적인 GoogleUserInfo 구조를 테스트합니다.
        GoogleUserInfo dummyGoogleInfo = createDummyGoogleInfo(code);
        return dummyGoogleInfo.toSocialUserInfo();
    }

    private GoogleUserInfo createDummyGoogleInfo(String code) {
        return new GoogleUserInfo() {
            @Override
            public String getId() {
                return "google_" + Math.abs(code.hashCode());
            }

            @Override
            public String getEmail() {
                return "google_" + Math.abs(code.hashCode()) + "@gmail.com";
            }

            @Override
            public String getName() {
                return "구글회원_" + code.substring(0, Math.min(code.length(), 5));
            }
        };
    }
}
