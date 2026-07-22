package com.teamfp.aistock.infra.oauth;

import com.teamfp.aistock.domain.user.entity.SocialProvider;
import com.teamfp.aistock.infra.oauth.dto.NaverUserInfo;
import com.teamfp.aistock.infra.oauth.dto.SocialUserInfo;
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
    public SocialUserInfo getUserInfo(String code) {
        log.info("네이버 소셜 로그인 유저 정보 획득 시작 - code: {}", code);
        
        // TODO: 실제 네이버 토큰 발급 및 사용자 프로필 API 조회 로직 구현부
        // RestTemplate 또는 WebClient를 사용해 아래 구조로 조회하여 파싱합니다.
        // ResponseEntity<NaverUserInfo> response = restTemplate.exchange(userInfoUrl, HttpMethod.GET, entity, NaverUserInfo.class);
        // return response.getBody().toSocialUserInfo();

        // 현재 단계에서는 Mock 데이터를 생성해 실제 타입 안정적인 NaverUserInfo 구조를 테스트합니다.
        NaverUserInfo dummyNaverInfo = createDummyNaverInfo(code);
        return dummyNaverInfo.toSocialUserInfo();
    }

    private NaverUserInfo createDummyNaverInfo(String code) {
        return new NaverUserInfo() {
            @Override
            public String getResultCode() {
                return "00";
            }

            @Override
            public String getMessage() {
                return "success";
            }

            @Override
            public NaverResponse getResponse() {
                return new NaverResponse() {
                    @Override
                    public String getId() {
                        return "naver_" + Math.abs(code.hashCode());
                    }

                    @Override
                    public String getEmail() {
                        return "naver_" + Math.abs(code.hashCode()) + "@naver.com";
                    }

                    @Override
                    public String getName() {
                        return "네이버회원_" + code.substring(0, Math.min(code.length(), 5));
                    }
                };
            }
        };
    }
}
