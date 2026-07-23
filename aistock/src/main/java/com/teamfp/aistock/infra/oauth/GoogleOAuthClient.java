package com.teamfp.aistock.infra.oauth;

import com.teamfp.aistock.domain.user.entity.SocialProvider;
import com.teamfp.aistock.infra.oauth.dto.GoogleUserDto;
import com.teamfp.aistock.infra.oauth.dto.SocialUserDto;
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
    public SocialUserDto getUserInfo(String code) {
        log.info("구글 소셜 로그인 유저 정보 획득 시작 - code: {}", code);
        
        // TODO: 실제 구글 토큰 발급 및 사용자 프로필 API 조회 로직 구현부
        // RestTemplate 또는 WebClient를 사용해 아래 구조로 조회하여 파싱합니다.
        // ResponseEntity<GoogleUserDto> response = restTemplate.exchange(userInfoUrl, HttpMethod.GET, entity, GoogleUserDto.class);
        // return response.getBody().toSocialUserDto();

        // 현재 단계에서는 Mock 데이터를 생성해 실제 타입 안정적인 GoogleUserDto 구조를 테스트합니다.
        GoogleUserDto dummyGoogleInfo = createDummyGoogleInfo(code);
        return dummyGoogleInfo.toSocialUserDto();
    }

    private GoogleUserDto createDummyGoogleInfo(String code) {
        return GoogleUserDto.builder()
                .id("google_" + Math.abs(code.hashCode()))
                .email("google_" + Math.abs(code.hashCode()) + "@gmail.com")
                .name("구글회원_" + code.substring(0, Math.min(code.length(), 5)))
                .build();
    }
}
