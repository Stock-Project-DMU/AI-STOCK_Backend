package com.teamfp.aistock.infra.oauth;

import com.teamfp.aistock.domain.user.entity.SocialProvider;
import com.teamfp.aistock.infra.oauth.dto.KakaoUserInfo;
import com.teamfp.aistock.infra.oauth.dto.SocialUserInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class KakaoOAuthClient implements OAuthClient {

    @Override
    public SocialProvider getProvider() {
        return SocialProvider.KAKAO;
    }

    @Override
    public SocialUserInfo getUserInfo(String code) {
        log.info("카카오 소셜 로그인 유저 정보 획득 시작 - code: {}", code);
        
        // TODO: 실제 카카오 토큰 발급 및 사용자 프로필 API 조회 로직 구현부
        // RestTemplate 또는 WebClient를 사용해 아래 구조로 조회하여 파싱합니다.
        // ResponseEntity<KakaoUserInfo> response = restTemplate.exchange(userInfoUrl, HttpMethod.GET, entity, KakaoUserInfo.class);
        // return response.getBody().toSocialUserInfo();

        // 현재 단계에서는 Mock 데이터를 생성해 실제 타입 안정적인 KakaoUserInfo 구조를 테스트합니다.
        KakaoUserInfo dummyKakaoInfo = createDummyKakaoInfo(code);
        return dummyKakaoInfo.toSocialUserInfo();
    }

    private KakaoUserInfo createDummyKakaoInfo(String code) {
        // 실제 KakaoUserInfo가 Jackson에 의해 JSON 바인딩된 후의 객체 구조 시뮬레이션
        try {
            // 리플렉션이나 생성자가 없으므로 빌더나 직접 생성이 곤란한 Jackson 전용 객체인 경우
            // Mock 데이터 조립 로직을 제공합니다.
            return new KakaoUserInfo() {
                @Override
                public Long getId() {
                    return (long) Math.abs(code.hashCode());
                }

                @Override
                public KakaoAccount getKakaoAccount() {
                    return new KakaoAccount() {
                        @Override
                        public String getEmail() {
                            return "kakao_" + Math.abs(code.hashCode()) + "@kakao.com";
                        }

                        @Override
                        public Profile getProfile() {
                            return new Profile() {
                                @Override
                                public String getNickname() {
                                    return "카카오회원_" + code.substring(0, Math.min(code.length(), 5));
                                }
                            };
                        }
                    };
                }
            };
        } catch (Exception e) {
            log.error("더미 카카오 정보 생성 에러", e);
            return new KakaoUserInfo();
        }
    }
}
