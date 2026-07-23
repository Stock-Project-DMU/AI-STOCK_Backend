package com.teamfp.aistock.infra.oauth;

import com.teamfp.aistock.domain.user.entity.SocialProvider;
import com.teamfp.aistock.infra.oauth.dto.NaverUserDto;
import com.teamfp.aistock.infra.oauth.dto.SocialUserDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class NaverOAuthClient implements OAuthClient {

    @Override
    public SocialProvider getProvider() {
        return SocialProvider.NAVER;
    }

    @Override
    public SocialUserDto getUserInfo(String code) {
        log.info("네이버 소셜 로그인 유저 정보 획득 시작 - code: {}", code);
        
        // TODO: 실제 네이버 토큰 발급 및 사용자 프로필 API 조회 로직 구현부
        // RestTemplate 또는 WebClient를 사용해 아래 구조로 조회하여 파싱합니다.
        // ResponseEntity<NaverUserDto> response = restTemplate.exchange(userInfoUrl, HttpMethod.GET, entity, NaverUserDto.class);
        // return response.getBody().toSocialUserDto();

        // 현재 단계에서는 Mock 데이터를 생성해 실제 타입 안정적인 NaverUserDto 구조를 테스트합니다.
        NaverUserDto dummyNaverInfo = createDummyNaverInfo(code);
        return dummyNaverInfo.toSocialUserDto();
    }

    private NaverUserDto createDummyNaverInfo(String code) {
        return NaverUserDto.builder()
                .resultCode("00")
                .message("success")
                .response(NaverUserDto.NaverResponse.builder()
                        .id("naver_" + Math.abs(code.hashCode()))
                        .email("naver_" + Math.abs(code.hashCode()) + "@naver.com")
                        .name("네이버회원_" + code.substring(0, Math.min(code.length(), 5)))
                        .build())
                .build();
    }
}
